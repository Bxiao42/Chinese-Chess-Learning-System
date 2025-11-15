package com.example.xiangqi.game;

import com.example.xiangqi.game.XqRules.*;
import org.springframework.stereotype.Component;

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
import java.util.Map;  // Added Map import
import java.util.HashMap;  // Added HashMap import

@Component
public class XqIndividualJuge {

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
     * Check game state - for human vs human games
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

        // Check for insufficient material (face-to-face generals and other special draw situations)
        if (isInsufficientMaterial(board)) {
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
     * Check for insufficient material (face-to-face generals, etc.)
     */
    private static boolean isInsufficientMaterial(Board board) {
        // Simplified version: check if only two generals remain
        int redPieceCount = 0;
        int blackPieceCount = 0;

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    if (piece.side == Side.RED) {
                        redPieceCount++;
                    } else {
                        blackPieceCount++;
                    }
                }
            }
        }

        // If both sides only have generals left, declare draw
        return redPieceCount == 1 && blackPieceCount == 1;
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
     * Export foul records to CSV - only includes number and type
     */
    public static String exportFoulsToCsv(List<String[]> foulRecords, String player, LocalDateTime endTime) {
        // Check if there are foul records
        if (foulRecords == null || foulRecords.isEmpty()) {
            System.out.println("No foul records for " + player + " side, CSV file not generated");
            return null;
        }

        try {
            // Create corresponding CSV directory in resources
            String dirPath = "red".equals(player) ?
                    "src/main/resources/CSV_Individual_ZeroShot_Red" :
                    "src/main/resources/CSV_Individual_ZeroShot_Black";

            Path csvDir = Paths.get(dirPath);
            if (!Files.exists(csvDir)) {
                Files.createDirectories(csvDir);
            }

            String fileName = String.format("%s_foul.csv", player);
            Path filePath = csvDir.resolve(fileName);

            // Write with UTF-8 encoding
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile());
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {

                // Write UTF-8 BOM
                writer.write('\uFEFF');

                // Simplified header - only includes number and type
                writer.write("Foul Number,Foul Type\n");

                // Write foul records - only include number and type
                for (String[] foul : foulRecords) {
                    writer.write(String.format("%s,%s\n",
                            foul[0], // Foul number
                            foul[1]  // Foul type
                    ));
                }
            }

            System.out.println(player + " side foul CSV file generated: " + filePath.toString());
            return filePath.toString();

        } catch (IOException e) {
            System.err.println("Failed to generate CSV file: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Export AI analysis results to CSV
     */
    public static String exportAiAnalysisToCsv(List<Map<String, String>> analysisRecords, String player, LocalDateTime endTime) {
        // Check if there are analysis records
        if (analysisRecords == null || analysisRecords.isEmpty()) {
            System.out.println("No AI analysis records for " + player + " side, CSV file not generated");
            return null;
        }

        try {
            // Create corresponding CSV directory in resources
            String dirPath = "red".equals(player) ?
                    "src/main/resources/CSV_Individual_ZeroShot_Red" :
                    "src/main/resources/CSV_Individual_ZeroShot_Black";

            Path csvDir = Paths.get(dirPath);
            if (!Files.exists(csvDir)) {
                Files.createDirectories(csvDir);
            }

            String fileName = String.format("%s_best.csv", player);
            Path filePath = csvDir.resolve(fileName);

            // Write with UTF-8 encoding
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile());
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {

                // Write UTF-8 BOM
                writer.write('\uFEFF');

                // Write header
                writer.write("Position,Best Move,Win Rate,Explanation\n");

                // Write analysis records
                for (Map<String, String> analysis : analysisRecords) {
                    writer.write(String.format("%s,%s,%s,%s\n",
                            analysis.getOrDefault("situation", ""),
                            analysis.getOrDefault("best_move", ""),
                            analysis.getOrDefault("win_rate", ""),
                            analysis.getOrDefault("explanation", "")
                    ));
                }
            }

            System.out.println(player + " side AI analysis CSV file generated: " + filePath.toString());
            return filePath.toString();

        } catch (IOException e) {
            System.err.println("Failed to generate AI analysis CSV file: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Get game result description - for human vs human games
     */
    public static String getResultDescription(GameResult result, Side playerSide) {
        switch (result) {
            case RED_WIN:
                return "Red wins!";
            case BLACK_WIN:
                return "Black wins!";
            case DRAW:
                return "Draw!";
            default:
                return "Game in progress";
        }
    }

    /**
     * Check if in check
     */
    public static boolean isInCheck(Board board, Side side) {
        return board.inCheck(side);
    }
}