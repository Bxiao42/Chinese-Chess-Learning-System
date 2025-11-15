// XqController.java
package com.example.xiangqi.web;

import com.example.xiangqi.engine.EngineService;
import com.example.xiangqi.game.XqPlayJuge;
import com.example.xiangqi.game.XqRules.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/xq")
@CrossOrigin(origins = "*")
public class XqController {

    private final EngineService engine;

    // Game state
    private Board board = Board.initial();      // Current board
    private Side  turn  = Side.RED;             // Whose turn
    private int   foulCount = 0;                // Foul count
    private final List<String> moveHistory = new ArrayList<>(); // UCI move records

    // New: game state tracking
    private boolean gameOver = false;
    private String winner = null;
    private String gameResult = null;
    private final List<String[]> foulRecords = new ArrayList<>(); // Foul records

    // New: perpetual check/chase detection
    private final Map<String, Integer> positionCounts = new HashMap<>(); // Position repetition count
    private String lastRepeatedPosition = null; // Last repeated position
    private boolean isRepeatedMove = false;     // Whether in repeated move state

    public XqController(EngineService engine) {
        this.engine = engine;
    }

    /** New game */
    @PostMapping("/new")
    public Map<String, Object> newGame() {
        board = Board.initial();
        turn = Side.RED;
        foulCount = 0;
        moveHistory.clear();
        gameOver = false;
        winner = null;
        gameResult = null;
        foulRecords.clear();
        positionCounts.clear(); // New: clear position count
        lastRepeatedPosition = null;
        isRepeatedMove = false;

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "new");
        resp.put("turn", turn.toString());
        resp.put("foul", foulCount);
        resp.put("gameOver", gameOver);
        return resp;
    }

    /** Player move */
    @PostMapping("/move")
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

        // 1) Legality check
        boolean isLegal = legal.stream().anyMatch(x -> x.to.equals(m.to));
        if (!isLegal) {
            foulCount++;
            foulRecords.add(new String[]{String.valueOf(foulCount), "Illegal move"});

            resp.put("result", "foul");
            resp.put("foulCount", foulCount);
            resp.put("message", "Illegal move! Please choose a legal move");
            return resp;
        }

        // New: check if in repeated move state
        String currentPosition = getBoardPosition(board, turn);
        if (isRepeatedMove && lastRepeatedPosition != null &&
                currentPosition.equals(lastRepeatedPosition)) {
            // Still in repeated move, not allowed
            resp.put("result", "repeated_move");
            resp.put("message", "Perpetual check or chase prohibited! Please choose another move");
            resp.put("repeatedPosition", currentPosition);
            return resp;
        }

        // 2) Player legal move
        Board newBoard = board.makeMove(m);
        String playerMoveUci = coordToUci(m);

        // New: check position repetition
        String newPosition = getBoardPosition(newBoard, turn.opponent());
        int repeatCount = positionCounts.getOrDefault(newPosition, 0) + 1;
        positionCounts.put(newPosition, repeatCount);

        // Check if repetition threshold reached (5 times)
        if (repeatCount >= 5) {
            isRepeatedMove = true;
            lastRepeatedPosition = newPosition;
            resp.put("result", "repeated_move");
            resp.put("message", "Perpetual check or chase prohibited! Please choose another move");
            resp.put("repeatedPosition", newPosition);
            resp.put("repeatCount", repeatCount);
            return resp;
        } else {
            isRepeatedMove = false;
            lastRepeatedPosition = null;
        }

        // Apply move
        board = newBoard;
        moveHistory.add(playerMoveUci);
        turn = turn.opponent();

        // New: check stalemate (no legal moves)
        if (isStalemate(board, turn)) {
            gameOver = true;
            winner = turn.opponent().toString(); // Opponent wins
            gameResult = turn.opponent() == Side.RED ? "Red wins (stalemate)" : "Black wins (stalemate)";

            resp.put("result", "game_over");
            resp.put("gameResult", gameResult);
            resp.put("winner", winner);
            resp.put("resultDescription", "Stalemate - " + (turn.opponent() == Side.RED ? "Red" : "Black") + " wins");
            return resp;
        }

        // Check game state after player move
        XqPlayJuge.GameResult gameState = checkGameState();
        if (gameState != XqPlayJuge.GameResult.IN_PROGRESS) {
            handleGameEnd(gameState, resp);
            resp.put("playerMove", playerMoveUci);
            return resp;
        }

        // 3) Let Pikafish make a move on current position
        String bestMoveUci = engine.bestMove(null, moveHistory, true);

        // 4) Apply AI move to our rule board
        if (bestMoveUci != null && !bestMoveUci.isEmpty()) {
            Move aiMove = parseUci(bestMoveUci);
            if (aiMove != null) {
                Board aiBoard = board.makeMove(aiMove);

                // New: check position repetition after AI move
                String aiPosition = getBoardPosition(aiBoard, turn);
                int aiRepeatCount = positionCounts.getOrDefault(aiPosition, 0) + 1;
                positionCounts.put(aiPosition, aiRepeatCount);

                if (aiRepeatCount >= 5) {
                    // AI perpetual check, player wins
                    gameOver = true;
                    winner = Side.RED.toString(); // Player plays red
                    gameResult = "Red wins (AI perpetual check)";

                    resp.put("result", "game_over");
                    resp.put("gameResult", gameResult);
                    resp.put("winner", winner);
                    resp.put("resultDescription", "AI perpetual check foul - Red wins");
                    return resp;
                }

                board = aiBoard;
                moveHistory.add(bestMoveUci);
                turn = turn.opponent();
            }
        }

        // 5) Check game state after AI move
        gameState = checkGameState();
        if (gameState != XqPlayJuge.GameResult.IN_PROGRESS) {
            handleGameEnd(gameState, resp);
            resp.put("playerMove", playerMoveUci);
            resp.put("aiMove", bestMoveUci);
            return resp;
        }

        // 6) Return to frontend for update
        resp.put("result", "ok");
        resp.put("playerMove", playerMoveUci);
        resp.put("aiMove", bestMoveUci != null ? bestMoveUci : "");
        resp.put("foulCount", foulCount);
        resp.put("isRepeatedMove", isRepeatedMove);

        // Add check state information
        boolean playerInCheck = XqPlayJuge.isInCheck(board, Side.RED);  // Player plays red
        boolean aiInCheck = XqPlayJuge.isInCheck(board, Side.BLACK);
        resp.put("meInCheck", playerInCheck);
        resp.put("oppInCheck", aiInCheck);

        return resp;
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

    /** Check game state */
    private XqPlayJuge.GameResult checkGameState() {
        return XqPlayJuge.checkGameState(board, turn);
    }

    /** Handle game end */
    private void handleGameEnd(XqPlayJuge.GameResult gameState, Map<String, Object> resp) {
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

        // Only generate CSV file if there are foul records
        if (foulRecords != null && !foulRecords.isEmpty()) {
            // Automatically generate CSV file (don't notify user)
            XqPlayJuge.exportFoulsToCsv(foulRecords, LocalDateTime.now());
        }

        resp.put("result", "game_over");
        resp.put("gameResult", gameResult);
        resp.put("winner", winner);
        resp.put("resultDescription", XqPlayJuge.getResultDescription(gameState, Side.RED)); // Player plays red
    }

    /* ---------- Utilities: Coordinates <-> UCI ---------- */

    private String coordToUci(Move m) {
        // Columns a..i, rows 9..0 (consistent with common Xiangqi coordinates)
        return "" + (char)('a' + m.from.c) + (9 - m.from.r)
                + (char)('a' + m.to.c)   + (9 - m.to.r);
    }

    private Move parseUci(String uci) {
        if (uci == null || uci.length() < 4) return null;
        int fromC = uci.charAt(0) - 'a';
        int fromR = 9 - (uci.charAt(1) - '0');
        int toC = uci.charAt(2) - 'a';
        int toR = 9 - (uci.charAt(3) - '0');
        return new Move(new Pos(fromR, fromC), new Pos(toR, toC));
    }
}