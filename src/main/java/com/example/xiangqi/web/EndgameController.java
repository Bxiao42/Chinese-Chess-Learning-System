// EndgameController.java
package com.example.xiangqi.web;

import com.example.xiangqi.engine.EngineService;
import com.example.xiangqi.game.XqEndgameJudge;
import com.example.xiangqi.game.XqEndgameJudge.GameResult;
import com.example.xiangqi.game.XqRules.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/endgame")
@CrossOrigin(origins = "*")
public class EndgameController {

    private final EngineService engine;

    // Endgame state storage
    private Map<Integer, EndgameState> endgameStates = new HashMap<>();

    // New: level total steps record
    private final Map<Integer, Integer> levelTotalSteps = new HashMap<>();

    public EndgameController(EngineService engine) {
        this.engine = engine;
        // Load existing total steps records on initialization
        loadTotalSteps();
    }

    /**
     * Endgame state class
     */
    private static class EndgameState {
        Board board;                    // Current board
        Side turn;                      // Whose turn
        int foulCount = 0;              // Foul count
        List<String> moveHistory = new ArrayList<>(); // UCI move records
        List<String[]> foulRecords = new ArrayList<>(); // Foul records
        int level;                      // Level number
        boolean completed = false;      // Whether completed
        Side playerSide;                // Player side
        GameResult finalResult;         // Final result
        String csvFilePath;             // CSV file path

        // New: perpetual check/chase detection
        Map<String, Integer> positionCounts = new HashMap<>(); // Position repetition count
        String lastRepeatedPosition = null; // Last repeated position
        boolean isRepeatedMove = false;     // Whether in repeated move state

        EndgameState(Board board, Side turn, int level, Side playerSide) {
            this.board = board;
            this.turn = turn;
            this.level = level;
            this.playerSide = playerSide;
        }
    }

    /** Load endgame level */
    @PostMapping("/load/{level}")
    public Map<String, Object> loadLevel(@PathVariable int level) {
        try {
            // Load endgame configuration from JS file
            EndgameConfig config = loadEndgameConfig(level);
            if (config == null) {
                throw new IllegalArgumentException("Level " + level + " does not exist");
            }

            // Create endgame board
            Board board = createBoardFromConfig(config);

            // Player uses the starting side by default
            Side playerSide = config.startingSide;

            EndgameState state = new EndgameState(board, config.startingSide, level, playerSide);
            endgameStates.put(level, state);

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "loaded");
            resp.put("level", level);
            resp.put("turn", state.turn.toString());
            resp.put("playerSide", playerSide.toString());
            resp.put("description", config.description != null ? config.description : "Endgame Level " + level);
            resp.put("board", serializeBoard(board));

            // New: return level total steps
            int totalSteps = levelTotalSteps.getOrDefault(level, 0);
            resp.put("totalSteps", totalSteps);

            return resp;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load level: " + e.getMessage());
        }
    }

    /** Endgame move */
    @PostMapping("/move/{level}")
    public Map<String, Object> playerMove(
            @PathVariable int level,
            @RequestBody Map<String, Integer> move) throws Exception {

        EndgameState state = endgameStates.get(level);
        if (state == null) {
            throw new IllegalArgumentException("Please load level " + level + " first");
        }

        if (state.completed) {
            throw new IllegalStateException("This level has been completed");
        }

        int fromR = move.get("fromR");
        int fromC = move.get("fromC");
        int toR = move.get("toR");
        int toC = move.get("toC");

        Move m = new Move(new Pos(fromR, fromC), new Pos(toR, toC));
        List<Move> legal = state.board.legalMovesAt(m.from);

        Map<String, Object> resp = new HashMap<>();

        // 1) Legality check
        boolean isLegal = legal.stream().anyMatch(x -> x.to.equals(m.to));
        if (!isLegal) {
            state.foulCount++;
            String foulTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            state.foulRecords.add(new String[]{
                    String.valueOf(state.foulCount),
                    "Illegal move",
                    foulTime
            });

            resp.put("result", "foul");
            resp.put("foulCount", state.foulCount);
            resp.put("message", "Illegal move! Please follow Xiangqi rules");
            resp.put("board", serializeBoard(state.board)); // Ensure current board state is returned
            return resp;
        }

        // New: check if in repeated move state
        String currentPosition = getBoardPosition(state.board, state.turn);
        if (state.isRepeatedMove && state.lastRepeatedPosition != null &&
                currentPosition.equals(state.lastRepeatedPosition)) {
            // Still in repeated move, not allowed
            resp.put("result", "repeated_move");
            resp.put("message", "Perpetual check or chase prohibited! Please choose another move");
            resp.put("repeatedPosition", currentPosition);
            resp.put("board", serializeBoard(state.board));
            return resp;
        }

        // 2) Player legal move
        Board newBoard = state.board.makeMove(m);
        String playerMoveUci = coordToUci(m);

        // New: check position repetition
        String newPosition = getBoardPosition(newBoard, state.turn.opponent());
        int repeatCount = state.positionCounts.getOrDefault(newPosition, 0) + 1;
        state.positionCounts.put(newPosition, repeatCount);

        // Check if repetition threshold reached (5 times)
        if (repeatCount >= 5) {
            state.isRepeatedMove = true;
            state.lastRepeatedPosition = newPosition;
            resp.put("result", "repeated_move");
            resp.put("message", "Perpetual check or chase prohibited! Please choose another move");
            resp.put("repeatedPosition", newPosition);
            resp.put("repeatCount", repeatCount);
            resp.put("board", serializeBoard(state.board)); // Don't update board
            return resp;
        } else {
            state.isRepeatedMove = false;
            state.lastRepeatedPosition = null;
        }

        // Apply move
        state.board = newBoard;
        state.moveHistory.add(playerMoveUci);

        // New: check stalemate (no legal moves)
        if (isStalemate(state.board, state.turn.opponent())) {
            // Opponent has no legal moves, current side wins
            GameResult result = state.turn == Side.RED ? GameResult.RED_WIN : GameResult.BLACK_WIN;
            return handleGameEnd(state, result, resp, "Stalemate", m);
        }

        // 3) Use judge to check game state
        GameResult result = XqEndgameJudge.checkGameState(state.board, state.turn.opponent());
        if (result != GameResult.IN_PROGRESS) {
            return handleGameEnd(state, result, resp, "After player move", m);
        }

        state.turn = state.turn.opponent();

        // 4) AI move
        String currentFen = boardToFen(state.board, state.turn);
        String aiMoveUci = engine.bestMove(currentFen, state.moveHistory, true);

        Move aiMove = null;
        if (aiMoveUci != null && !aiMoveUci.isEmpty()) {
            aiMove = parseUci(aiMoveUci);
            if (aiMove != null) {
                Board aiBoard = state.board.makeMove(aiMove);

                // New: check position repetition after AI move
                String aiPosition = getBoardPosition(aiBoard, state.turn);
                int aiRepeatCount = state.positionCounts.getOrDefault(aiPosition, 0) + 1;
                state.positionCounts.put(aiPosition, aiRepeatCount);

                if (aiRepeatCount >= 5) {
                    // AI perpetual check, player wins
                    GameResult winResult = state.playerSide == Side.RED ? GameResult.RED_WIN : GameResult.BLACK_WIN;
                    return handleGameEnd(state, winResult, resp, "AI perpetual check foul", m);
                }

                state.board = aiBoard;
                state.moveHistory.add(aiMoveUci);

                // Use judge to check game state
                result = XqEndgameJudge.checkGameState(state.board, state.turn);
                if (result != GameResult.IN_PROGRESS) {
                    return handleGameEnd(state, result, resp, "After AI move", m);
                }

                state.turn = state.turn.opponent();
                resp.put("aiMove", aiMoveUci);
            }
        }

        // 5) Return result
        resp.put("result", "ok");
        resp.put("playerMove", playerMoveUci);
        resp.put("foulCount", state.foulCount);
        resp.put("turn", state.turn.toString());
        resp.put("board", serializeBoard(state.board));
        resp.put("isRepeatedMove", state.isRepeatedMove);

        // Check check state
        if (state.board.inCheck(state.turn)) {
            resp.put("inCheck", true);
            resp.put("message", "Check! Please escape check");
        }

        return resp;
    }

    /**
     * Handle game end logic
     */
    private Map<String, Object> handleGameEnd(EndgameState state, GameResult result,
                                              Map<String, Object> resp, String trigger, Move lastMove) {
        state.completed = true;
        state.finalResult = result;

        // Only generate CSV if there are foul records
        String csvFilePath = null;
        if (state.foulRecords != null && !state.foulRecords.isEmpty()) {
            csvFilePath = XqEndgameJudge.exportFoulsToCsv(
                    state.level, state.foulRecords, state.playerSide.toString(), LocalDateTime.now()
            );
        }

        state.csvFilePath = csvFilePath;

        // New: update level total steps
        updateLevelTotalSteps(state.level, state.moveHistory.size());

        // Set response - ensure latest board state is included
        resp.put("result", "gameOver");
        resp.put("gameResult", result.toString());
        resp.put("resultDescription", XqEndgameJudge.getResultDescription(result, state.playerSide));
        resp.put("completed", true);
        resp.put("csvFilePath", csvFilePath);
        resp.put("trigger", trigger);
        resp.put("foulCount", state.foulCount);
        resp.put("board", serializeBoard(state.board)); // Key: return final board state
        resp.put("playerMove", coordToUci(lastMove));

        System.out.println("Game ended: " + result.getDescription() + " (" + trigger + ")");
        System.out.println("Level " + state.level + " current steps: " + state.moveHistory.size() +
                ", cumulative total steps: " + levelTotalSteps.get(state.level));
        if (csvFilePath != null) {
            System.out.println("CSV file path: " + csvFilePath);
        }

        return resp;
    }

    /** Reset level */
    @PostMapping("/reset/{level}")
    public Map<String, Object> resetLevel(@PathVariable int level) {
        // Reload level
        return loadLevel(level);
    }

    /** Get level progress */
    @GetMapping("/progress/{level}")
    public Map<String, Object> getProgress(@PathVariable int level) {
        EndgameState state = endgameStates.get(level);
        Map<String, Object> resp = new HashMap<>();
        if (state != null) {
            resp.put("level", level);
            resp.put("completed", state.completed);
            resp.put("foulCount", state.foulCount);
            resp.put("moveCount", state.moveHistory.size());
            if (state.completed) {
                resp.put("finalResult", state.finalResult != null ? state.finalResult.toString() : "UNKNOWN");
                resp.put("resultDescription",
                        XqEndgameJudge.getResultDescription(state.finalResult, state.playerSide));
                resp.put("csvFilePath", state.csvFilePath);
            }
        } else {
            resp.put("level", level);
            resp.put("completed", false);
        }

        // New: return level total steps
        int totalSteps = levelTotalSteps.getOrDefault(level, 0);
        resp.put("totalSteps", totalSteps);

        return resp;
    }

    /** Download CSV file */
    @GetMapping("/download-csv/{level}")
    public ResponseEntity<Resource> downloadCsv(@PathVariable int level) {
        try {
            EndgameState state = endgameStates.get(level);
            if (state == null || state.csvFilePath == null) {
                return ResponseEntity.notFound().build();
            }

            Path filePath = Paths.get(state.csvFilePath);
            Resource resource = new org.springframework.core.io.UrlResource(filePath.toUri());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filePath.getFileName() + "\"")
                    .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8")) // Add UTF-8 encoding
                    .body(resource);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.notFound().build();
        }
    }

    /** New: Get level total steps statistics */
    @GetMapping("/total-steps")
    public Map<String, Object> getTotalSteps() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("levelTotalSteps", levelTotalSteps);
        return resp;
    }

    /** New: Load total steps records */
    private void loadTotalSteps() {
        try {
            Path csvDir = Paths.get("src/main/resources/CSV_Endgame_ZeroShot");
            if (!Files.exists(csvDir)) {
                Files.createDirectories(csvDir);
            }

            Path filePath = csvDir.resolve("endgame_total_steps.csv");

            if (!Files.exists(filePath)) {
                System.out.println("Total steps record file does not exist, will create new file");
                return;
            }

            try (BufferedReader reader = Files.newBufferedReader(filePath)) {
                String header = reader.readLine(); // Skip header
                if (header == null || !header.startsWith("level,total_steps")) {
                    System.out.println("Total steps record file format incorrect");
                    return;
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length == 2) {
                        try {
                            int level = Integer.parseInt(parts[0]);
                            int steps = Integer.parseInt(parts[1]);
                            levelTotalSteps.put(level, steps);
                        } catch (NumberFormatException e) {
                            System.err.println("Failed to parse total steps record: " + line);
                        }
                    }
                }
            }

            System.out.println("Loaded level total steps records: " + levelTotalSteps);
        } catch (IOException e) {
            System.err.println("Failed to load total steps records: " + e.getMessage());
        }
    }

    /** New: Update level total steps */
    private void updateLevelTotalSteps(int level, int currentSteps) {
        int totalSteps = levelTotalSteps.getOrDefault(level, 0) + currentSteps;
        levelTotalSteps.put(level, totalSteps);
        saveTotalSteps();
    }

    /** New: Save total steps records to file */
    private void saveTotalSteps() {
        try {
            Path csvDir = Paths.get("src/main/resources/CSV_Endgame_ZeroShot");
            if (!Files.exists(csvDir)) {
                Files.createDirectories(csvDir);
            }

            Path filePath = csvDir.resolve("endgame_total_steps.csv");

            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                writer.write("level,total_steps\n");

                // Write sorted by level number
                levelTotalSteps.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> {
                            try {
                                writer.write(entry.getKey() + "," + entry.getValue() + "\n");
                            } catch (IOException e) {
                                System.err.println("Failed to write total steps record: " + e.getMessage());
                            }
                        });
            }

            System.out.println("Saved level total steps records: " + levelTotalSteps);
        } catch (IOException e) {
            System.err.println("Failed to save total steps records: " + e.getMessage());
        }
    }

    /** New: Check stalemate (no legal moves) */
    private boolean isStalemate(Board board, Side side) {
        // Traverse all pieces of this side on the board
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> legalMoves = board.legalMovesAt(new Pos(r, c));
                    if (!legalMoves.isEmpty()) {
                        return false; // Has legal moves
                    }
                }
            }
        }
        return true; // No legal moves
    }

    /** New: Get board position identifier (for repetition detection) */
    private String getBoardPosition(Board board, Side turn) {
        StringBuilder sb = new StringBuilder();
        sb.append(turn.toString()).append("|");

        // Simplified position representation (piece types and positions)
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    sb.append(piece.type.toString()).append(piece.side.toString())
                            .append(r).append(c).append(";");
                }
            }
        }
        return sb.toString();
    }

    /* ========== Utility Methods ========== */

    /**
     * Load endgame configuration from JS file
     */
    private EndgameConfig loadEndgameConfig(int level) throws IOException {
        // Try multiple possible paths
        String[] possiblePaths = {
                "static/images/" + level + ".js",
                "public/images/" + level + ".js",
                "resources/static/images/" + level + ".js"
        };

        for (String jsPath : possiblePaths) {
            ClassPathResource resource = new ClassPathResource(jsPath);

            if (resource.exists()) {
                try (InputStream inputStream = resource.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }

                    System.out.println("Loading level " + level + " from path " + jsPath);
                    return parseJsConfig(content.toString(), level);
                }
            }
        }

        // If JS file not found, throw exception
        throw new FileNotFoundException("JS configuration file for level " + level + " not found");
    }

    /**
     * Parse JS configuration - for new __exportLevel format
     */
    private EndgameConfig parseJsConfig(String jsContent, int level) {
        EndgameConfig config = new EndgameConfig();
        config.level = level;

        try {
            // Clean JS content, remove unnecessary spaces and newlines
            String cleanedContent = jsContent.replaceAll("\\s+", " ").trim();

            // Remove comments
            cleanedContent = cleanedContent.replaceAll("//.*", "");
            cleanedContent = cleanedContent.replaceAll("/\\*.*?\\*/", "");

            System.out.println("Cleaned JS content: " + cleanedContent);

            // Extract side information - for __exportLevel format
            Pattern sidePattern = Pattern.compile("side:\\s*[\"']?(RED|BLACK)[\"']?");
            Matcher sideMatcher = sidePattern.matcher(cleanedContent);
            if (sideMatcher.find()) {
                String sideStr = sideMatcher.group(1);
                config.startingSide = "RED".equals(sideStr) ? Side.RED : Side.BLACK;
                System.out.println("Found starting side: " + sideStr);
            } else {
                config.startingSide = Side.RED; // Default red first
                System.out.println("Using default starting side: RED");
            }

            // Extract pieces array
            config.pieces = new ArrayList<>();

            // Find pieces array start and end positions
            int piecesStart = cleanedContent.indexOf("pieces: [");
            if (piecesStart == -1) {
                throw new IllegalArgumentException("Pieces array not found");
            }

            int bracketCount = 1;
            int piecesEnd = piecesStart + "pieces: [".length();

            // Find matching closing bracket
            for (int i = piecesEnd; i < cleanedContent.length(); i++) {
                char c = cleanedContent.charAt(i);
                if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;

                if (bracketCount == 0) {
                    piecesEnd = i;
                    break;
                }
            }

            String piecesSection = cleanedContent.substring(piecesStart + "pieces: [".length(), piecesEnd);
            System.out.println("Pieces section: " + piecesSection);

            // Parse piece objects
            Pattern piecePattern = Pattern.compile(
                    "\\{\\s*type:\\s*[\"']?([KABNRCPH])[\"']?\\s*,\\s*side:\\s*[\"']?(RED|BLACK)[\"']?\\s*,\\s*r:\\s*(\\d+)\\s*,\\s*c:\\s*(\\d+)\\s*\\}"
            );

            Matcher pieceMatcher = piecePattern.matcher(piecesSection);
            while (pieceMatcher.find()) {
                try {
                    String type = pieceMatcher.group(1);
                    String sideStr = pieceMatcher.group(2);
                    int r = Integer.parseInt(pieceMatcher.group(3));
                    int c = Integer.parseInt(pieceMatcher.group(4));

                    Side side = "RED".equals(sideStr) ? Side.RED : Side.BLACK;
                    config.pieces.add(new PiecePlacement(type, side, r, c));
                    System.out.println("Parsed piece: " + type + " " + sideStr + " position(" + r + "," + c + ")");
                } catch (Exception e) {
                    System.err.println("Failed to parse piece: " + e.getMessage());
                }
            }

            // If no pieces found, throw exception
            if (config.pieces.isEmpty()) {
                throw new IllegalArgumentException("No piece definitions found in JS configuration for level " + level);
            }

            config.description = "Endgame Level " + level;
            System.out.println("Successfully loaded level " + level + ", found " + config.pieces.size() + " pieces");
            return config;

        } catch (Exception e) {
            System.err.println("Failed to parse JS configuration: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to parse level " + level + " configuration: " + e.getMessage());
        }
    }

    /**
     * Create board from configuration
     */
    private Board createBoardFromConfig(EndgameConfig config) {
        Board board = new Board();

        // Clear board
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                board.set(r, c, null);
            }
        }

        // Place pieces
        for (PiecePlacement placement : config.pieces) {
            Piece piece = createPiece(placement.type, placement.side, placement.r, placement.c);
            if (piece != null) {
                board.set(placement.r, placement.c, piece);
                System.out.println("Placed piece: " + placement.type + " " + placement.side + " at position(" + placement.r + "," + placement.c + ")");
            }
        }

        return board;
    }

    /**
     * Create piece object
     */
    private Piece createPiece(String type, Side side, int r, int c) {
        Pos pos = new Pos(r, c);
        switch (type) {
            case "K": return new General(side, pos);
            case "A": return new Advisor(side, pos);
            case "B": return new Elephant(side, pos);
            case "N": return new Horse(side, pos);
            case "R": return new Rook(side, pos);
            case "C": return new Cannon(side, pos);
            case "P": return new Pawn(side, pos);
            case "H": return new Horse(side, pos); // H also maps to Horse
            default: return null;
        }
    }

    /**
     * Serialize board state for frontend
     */
    private List<List<Map<String, Object>>> serializeBoard(Board board) {
        List<List<Map<String, Object>>> serialized = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            List<Map<String, Object>> row = new ArrayList<>();
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                Map<String, Object> cell = new HashMap<>();
                if (piece != null) {
                    cell.put("side", piece.side.toString());
                    cell.put("type", pieceTypeToChar(piece.type));
                } else {
                    cell.put("side", null);
                    cell.put("type", null);
                }
                row.add(cell);
            }
            serialized.add(row);
        }
        return serialized;
    }

    private String pieceTypeToChar(PieceType type) {
        switch (type) {
            case GENERAL: return "K";
            case ADVISOR: return "A";
            case ELEPHANT: return "B";
            case HORSE: return "N";
            case ROOK: return "R";
            case CANNON: return "C";
            case PAWN: return "P";
            default: return "?";
        }
    }

    /**
     * Convert board to FEN string (simplified version)
     */
    private String boardToFen(Board board, Side turn) {
        // Simplified implementation - generate basic FEN string
        StringBuilder fen = new StringBuilder();

        // Board section
        for (int r = 0; r < 10; r++) {
            int emptyCount = 0;
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece == null) {
                    emptyCount++;
                } else {
                    if (emptyCount > 0) {
                        fen.append(emptyCount);
                        emptyCount = 0;
                    }
                    char pieceChar = getPieceChar(piece);
                    fen.append(pieceChar);
                }
            }
            if (emptyCount > 0) {
                fen.append(emptyCount);
            }
            if (r < 9) {
                fen.append('/');
            }
        }

        // Turn
        fen.append(' ').append(turn == Side.RED ? 'w' : 'b');

        return fen.toString();
    }

    private char getPieceChar(Piece piece) {
        char baseChar;
        switch (piece.type) {
            case ROOK: baseChar = 'r'; break;
            case HORSE: baseChar = 'n'; break;
            case ELEPHANT: baseChar = 'b'; break;
            case ADVISOR: baseChar = 'a'; break;
            case GENERAL: baseChar = 'k'; break;
            case CANNON: baseChar = 'c'; break;
            case PAWN: baseChar = 'p'; break;
            default: baseChar = '?';
        }
        return piece.side == Side.RED ? Character.toUpperCase(baseChar) : baseChar;
    }

    /* ---------- UCI Conversion Utilities ---------- */

    private String coordToUci(Move m) {
        return "" + (char)('a' + m.from.c) + (9 - m.from.r)
                + (char)('a' + m.to.c)   + (9 - m.to.r);
    }

    private Move parseUci(String uci) {
        if (uci == null || uci.length() < 4) return null;
        int fromC = uci.charAt(0) - 'a';
        int fromR = 9 - (uci.charAt(1) - '0');
        int toC   = uci.charAt(2) - 'a';
        int toR   = 9 - (uci.charAt(3) - '0');
        return new Move(new Pos(fromR, fromC), new Pos(toR, toC));
    }

    /**
     * Endgame configuration class
     */
    private static class EndgameConfig {
        int level;
        Side startingSide;
        List<PiecePlacement> pieces;
        String description;
    }

    private static class PiecePlacement {
        String type;
        Side side;
        int r, c;

        PiecePlacement() {}

        PiecePlacement(String type, Side side, int r, int c) {
            this.type = type;
            this.side = side;
            this.r = r;
            this.c = c;
        }
    }
}