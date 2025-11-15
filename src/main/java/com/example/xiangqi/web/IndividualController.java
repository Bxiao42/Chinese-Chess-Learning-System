package com.example.xiangqi.web;

import com.example.xiangqi.game.XqIndividualJuge;
import com.example.xiangqi.game.XqRules.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.time.LocalDateTime;
import java.util.*;

@Controller
@RequestMapping("/individual")
public class IndividualController {

    // Game state
    private Board board = Board.initial();
    private Side turn = Side.RED;
    private boolean gameOver = false;
    private String winner = null;
    private String gameResult = null;
    private final List<String[]> redFoulRecords = new ArrayList<>();
    private final List<String[]> blackFoulRecords = new ArrayList<>();

    // New: position repetition detection
    private final Map<String, Integer> positionCounts = new HashMap<>();
    private String lastRepeatedPosition = null;
    private boolean isRepeatedMove = false;

    public IndividualController() {
        // Remove EngineService dependency
    }

    @GetMapping
    public String individual() {
        return "individual";
    }

    @GetMapping("/black/best")
    @ResponseBody
    public List<Map<String, String>> getBlackBestMoves() {
        return readCSV("CSV_Individual_ZeroShot_Black/black_best.csv");
    }

    @GetMapping("/black/foul")
    @ResponseBody
    public List<Map<String, String>> getBlackFoulMoves() {
        return readCSV("CSV_Individual_ZeroShot_Black/black_foul.csv");
    }

    @GetMapping("/red/best")
    @ResponseBody
    public List<Map<String, String>> getRedBestMoves() {
        return readCSV("CSV_Individual_ZeroShot_Red/red_best.csv");
    }

    @GetMapping("/red/foul")
    @ResponseBody
    public List<Map<String, String>> getRedFoulMoves() {
        return readCSV("CSV_Individual_ZeroShot_Red/red_foul.csv");
    }

    @PostMapping("/new")
    @ResponseBody
    public Map<String, Object> newGame() {
        board = Board.initial();
        turn = Side.RED;
        gameOver = false;
        winner = null;
        gameResult = null;
        redFoulRecords.clear();
        blackFoulRecords.clear();
        positionCounts.clear();
        lastRepeatedPosition = null;
        isRepeatedMove = false;

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "new");
        resp.put("turn", turn.toString());
        resp.put("gameOver", gameOver);
        return resp;
    }

    @PostMapping("/move")
    @ResponseBody
    public Map<String, Object> playerMove(@RequestBody Map<String, Integer> move) throws Exception {
        Map<String, Object> resp = new HashMap<>();

        // Check if game has ended
        if (gameOver) {
            resp.put("result", "game_over");
            resp.put("gameResult", gameResult);
            resp.put("winner", winner);
            return resp;
        }

        int fromR = move.get("fromR");
        int fromC = move.get("fromC");
        int toR = move.get("toR");
        int toC = move.get("toC");

        Move m = new Move(new Pos(fromR, fromC), new Pos(toR, toC));
        List<Move> legal = board.legalMovesAt(m.from);

        // Legality check
        boolean isLegal = legal.stream().anyMatch(x -> x.to.equals(m.to));
        if (!isLegal) {
            // Record foul - fix numbering issue
            String foulType = "Illegal move";
            String player = turn == Side.RED ? "red" : "black";

            // Determine which foul record list to use based on player side
            List<String[]> foulRecords = turn == Side.RED ? redFoulRecords : blackFoulRecords;
            int foulNumber = foulRecords.size() + 1; // Continuous numbering starting from 1

            foulRecords.add(new String[]{String.valueOf(foulNumber), foulType});

            resp.put("result", "foul");
            resp.put("foulCount", foulNumber);
            resp.put("message", "Illegal move! Please choose a legal move");
            resp.put("player", player);
            return resp;
        }

        // Check if in repeated move state
        String currentPosition = getBoardPosition(board, turn);
        if (isRepeatedMove && lastRepeatedPosition != null &&
                currentPosition.equals(lastRepeatedPosition)) {
            // Still in repeated move, not allowed
            String player = turn == Side.RED ? "red" : "black";
            resp.put("result", "repeated_move");
            resp.put("message", "Perpetual check or chase prohibited! Please choose another move");
            resp.put("repeatedPosition", currentPosition);
            resp.put("player", player);
            return resp;
        }

        // Execute move
        Board newBoard = board.makeMove(m);
        String moveUci = coordToUci(m);

        // Check position repetition
        String newPosition = getBoardPosition(newBoard, turn.opponent());
        int repeatCount = positionCounts.getOrDefault(newPosition, 0) + 1;
        positionCounts.put(newPosition, repeatCount);

        // Check if repetition threshold reached (5 times)
        if (repeatCount >= 5) {
            isRepeatedMove = true;
            lastRepeatedPosition = newPosition;
            String player = turn == Side.RED ? "red" : "black";
            resp.put("result", "repeated_move");
            resp.put("message", "Perpetual check or chase prohibited! Please choose another move");
            resp.put("repeatedPosition", newPosition);
            resp.put("repeatCount", repeatCount);
            resp.put("player", player);
            return resp;
        } else {
            isRepeatedMove = false;
            lastRepeatedPosition = null;
        }

        // Apply move
        board = newBoard;
        turn = turn.opponent();

        // Check game state
        XqIndividualJuge.GameResult gameState = checkGameState();
        if (gameState != XqIndividualJuge.GameResult.IN_PROGRESS) {
            handleGameEnd(gameState, resp);
            resp.put("move", moveUci);
            resp.put("player", turn.opponent().toString().toLowerCase());
            return resp;
        }

        // Return success response
        resp.put("result", "ok");
        resp.put("move", moveUci);
        resp.put("turn", turn.toString());
        resp.put("player", turn.opponent().toString().toLowerCase());

        // Add check state information
        boolean redInCheck = isInCheck(board, Side.RED);
        boolean blackInCheck = isInCheck(board, Side.BLACK);
        resp.put("redInCheck", redInCheck);
        resp.put("blackInCheck", blackInCheck);

        return resp;
    }

    /**
     * Get board position identifier (for repetition detection)
     */
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

    @PostMapping("/game/end")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> endGame(@RequestBody Map<String, Object> gameData) {
        try {
            String winner = (String) gameData.get("winner");
            String reason = (String) gameData.get("reason");

            // Process game end logic, generate foul CSV
            processGameEnd(winner, reason);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Game ended, foul records generated");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    private void processGameEnd(String winner, String reason) {
        // Only generate CSV files if there are foul records
        if ((redFoulRecords != null && !redFoulRecords.isEmpty()) ||
                (blackFoulRecords != null && !blackFoulRecords.isEmpty())) {

            // Generate CSV files - only include number and type
            if (!redFoulRecords.isEmpty()) {
                XqIndividualJuge.exportFoulsToCsv(redFoulRecords, "red", LocalDateTime.now());
            }
            if (!blackFoulRecords.isEmpty()) {
                XqIndividualJuge.exportFoulsToCsv(blackFoulRecords, "black", LocalDateTime.now());
            }
        }
    }

    private XqIndividualJuge.GameResult checkGameState() {
        return XqIndividualJuge.checkGameState(board, turn);
    }

    private boolean isInCheck(Board board, Side side) {
        return board.inCheck(side);
    }

    private void handleGameEnd(XqIndividualJuge.GameResult gameState, Map<String, Object> resp) {
        gameOver = true;

        switch (gameState) {
            case RED_WIN:
                winner = "RED";
                gameResult = "Red wins";
                break;
            case BLACK_WIN:
                winner = "BLACK";
                gameResult = "Black wins";
                break;
            case DRAW:
                gameResult = "Draw";
                break;
            default:
                gameResult = "Game ended";
        }

        resp.put("result", "game_over");
        resp.put("gameResult", gameResult);
        resp.put("winner", winner);
        resp.put("resultDescription", XqIndividualJuge.getResultDescription(gameState, Side.RED));
    }

    /* ---------- Utility Methods ---------- */

    private String coordToUci(Move m) {
        return "" + (char)('a' + m.from.c) + (9 - m.from.r)
                + (char)('a' + m.to.c)   + (9 - m.to.r);
    }

    private List<Map<String, String>> readCSV(String filePath) {
        List<Map<String, String>> data = new ArrayList<>();
        try {
            Resource resource = new ClassPathResource(filePath);
            if (!resource.exists()) {
                return data;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()));

            String line;
            boolean firstLine = true;
            String[] headers = new String[0];

            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");
                if (firstLine) {
                    headers = values;
                    firstLine = false;
                } else {
                    Map<String, String> row = new HashMap<>();
                    for (int i = 0; i < headers.length && i < values.length; i++) {
                        row.put(headers[i].trim(), values[i].trim());
                    }
                    data.add(row);
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return data;
    }
}