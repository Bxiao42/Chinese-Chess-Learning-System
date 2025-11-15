// XqEndgameJudge.java
package com.example.xiangqi.game;

import com.example.xiangqi.game.XqRules.*;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class XqEndgameJudge {

    /**
     * Game result enum
     */
    public enum GameResult {
        RED_WIN("Red wins"),
        BLACK_WIN("Black wins"),
        DRAW("Draw"),
        IN_PROGRESS("In progress");

        private final String description;

        GameResult(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Check game state
     */
    public static GameResult checkGameState(Board board, Side currentTurn) {
        // Check if current side is checkmated
        if (isCheckmated(board, currentTurn)) {
            return currentTurn == Side.RED ? GameResult.BLACK_WIN : GameResult.RED_WIN;
        }

        // Check for stalemate (no legal moves but not in check)
        if (isStalemate(board, currentTurn)) {
            return GameResult.DRAW;
        }

        return GameResult.IN_PROGRESS;
    }

    /**
     * Check if checkmated
     */
    private static boolean isCheckmated(Board board, Side side) {
        // 1. Check if currently in check
        if (!board.inCheck(side)) {
            return false;
        }

        // 2. Check if any legal moves can escape check
        return !hasLegalMoves(board, side);
    }

    /**
     * Check for stalemate (no legal moves but not in check)
     */
    private static boolean isStalemate(Board board, Side side) {
        // 1. Must not be in check
        if (board.inCheck(side)) {
            return false;
        }

        // 2. Check if no legal moves available
        return !hasLegalMoves(board, side);
    }

    /**
     * Check if specified side has any legal moves
     */
    private static boolean hasLegalMoves(Board board, Side side) {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> moves = board.legalMovesAt(new Pos(r, c));
                    if (!moves.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Export foul records to CSV (simplified version, only shows foul number and type)
     */
    public static String exportFoulsToCsv(int level, List<String[]> foulRecords, String playerSide, LocalDateTime endTime) {
        try {
            // Create CSV directory in resources directory for IDEA recognition
            Path csvDir = Paths.get("src/main/resources/CSV_Endgame_ZeroShot");
            if (!Files.exists(csvDir)) {
                Files.createDirectories(csvDir);
            }

            String fileName = String.format("endgame_%d_fouls_%s.csv",
                    level,
                    endTime.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));

            Path filePath = csvDir.resolve(fileName);

            // Write with UTF-8 encoding to resolve garbled characters
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile());
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {

                // Write UTF-8 BOM to ensure Excel recognizes encoding correctly
                writer.write('\uFEFF');

                // Simplified header - only shows foul number and type
                writer.write("Foul Number,Foul Type\n");

                // Write foul records
                for (int i = 0; i < foulRecords.size(); i++) {
                    String[] foul = foulRecords.get(i);
                    writer.write(String.format("%d,%s\n",
                            i + 1,
                            foul[1] // Foul type
                    ));
                }
            }

            System.out.println("CSV file generated: " + filePath.toString());
            return filePath.toString();

        } catch (IOException e) {
            System.err.println("Failed to generate CSV file: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Get game result description
     */
    public static String getResultDescription(GameResult result, Side playerSide) {
        switch (result) {
            case RED_WIN:
                return playerSide == Side.RED ? "Congratulations! You checkmated the AI!" : "AI checkmated you!";
            case BLACK_WIN:
                return playerSide == Side.BLACK ? "Congratulations! You checkmated the AI!" : "AI checkmated you!";
            case DRAW:
                return "Stalemate!";
            default:
                return "Game in progress";
        }
    }
}