package com.example.xiangqi.game;

import java.util.*;

/**
 * Single-file Chinese Chess rules engine:
 * - 10x9 coordinates: row 0 at top (Black baseline), row 9 at bottom (Red baseline), col 0..8 left to right
 * - Red moves upward (dir = -1), Black moves downward (dir = +1)
 * - Each piece class implements its own "pseudo-legal moves" (without considering check/face-to-face); Board will uniformly filter:
 *   1) Cannot capture own pieces
 *   2) Cannot have generals face each other after move
 *   3) Cannot be in check after move
 */
public class XqRules {

    /* ===================== Basic Types ===================== */

    public enum Side {
        RED(-1),   // Red at bottom, moves upward
        BLACK(+1); // Black at top, moves downward
        public final int dir;

        Side(int d) {
            this.dir = d;
        }

        public Side opponent() {
            return this == RED ? BLACK : RED;
        }
    }

    public enum PieceType {
        GENERAL, ADVISOR, ELEPHANT, HORSE, ROOK, CANNON, PAWN
    }

    /**
     * Position coordinates
     */
    public static final class Pos {
        public final int r, c;

        public Pos(int r, int c) {
            this.r = r;
            this.c = c;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pos p)) return false;
            return p.r == r && p.c == c;
        }

        @Override
        public int hashCode() {
            return Objects.hash(r, c);
        }

        @Override
        public String toString() {
            return "(" + r + "," + c + ")";
        }
    }

    /**
     * Move
     */
    public static final class Move {
        public final Pos from, to;

        public Move(Pos from, Pos to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return from + "->" + to;
        }
    }

    /* ===================== Piece Base Class ===================== */

    public static abstract class Piece {
        public final PieceType type;
        public final Side side;
        public Pos pos;

        protected Piece(PieceType type, Side side, Pos pos) {
            this.type = type;
            this.side = side;
            this.pos = pos;
        }

        /**
         * Generate only "rule-based" moves (without considering self-check, face-to-face). Board will filter for legality.
         */
        public abstract List<Pos> pseudoLegalMoves(Board b);

        /**
         * Utility: direction increment arrays
         */
        protected static final int[][] ORTHO = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        protected static final int[][] DIAG1 = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        /**
         * Utility: check if own piece
         */
        protected boolean isOwn(Board b, int r, int c) {
            Piece q = b.at(r, c);
            return q != null && q.side == this.side;
        }

        /**
         * Utility: check if enemy piece
         */
        protected boolean isEnemy(Board b, int r, int c) {
            Piece q = b.at(r, c);
            return q != null && q.side != this.side;
        }
    }

    /* ===================== Piece Rules ===================== */

    /**
     * Rook: moves orthogonally, stops at first piece; can capture enemies
     */
    public static final class Rook extends Piece {
        public Rook(Side s, Pos p) {
            super(PieceType.ROOK, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                while (b.in(r, c)) {
                    if (b.at(r, c) == null) out.add(new Pos(r, c));
                    else {
                        if (isEnemy(b, r, c)) out.add(new Pos(r, c));
                        break;
                    }
                    r += d[0];
                    c += d[1];
                }
            }
            return out;
        }
    }

    /**
     * Cannon: moves orthogonally; must jump over exactly one piece to capture; cannot jump when not capturing
     */
    public static final class Cannon extends Piece {
        public Cannon(Side s, Pos p) {
            super(PieceType.CANNON, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                boolean jumped = false;
                while (b.in(r, c)) {
                    Piece q = b.at(r, c);
                    if (!jumped) {
                        if (q == null) out.add(new Pos(r, c)); // Normal move
                        else jumped = true;                    // Cannon platform
                    } else {
                        if (q != null) {                       // Can capture first piece after platform
                            if (q.side != this.side) out.add(new Pos(r, c));
                            break;
                        }
                    }
                    r += d[0];
                    c += d[1];
                }
            }
            return out;
        }
    }

    /**
     * Horse: knight moves; blocked if adjacent "leg" position is occupied
     */
    public static final class Horse extends Piece {
        public Horse(Side s, Pos p) {
            super(PieceType.HORSE, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            int[][] legs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            int[][][] dsts = {
                    {{-2, -1}, {-2, 1}}, {{2, -1}, {2, 1}}, {{-1, -2}, {1, -2}}, {{-1, 2}, {1, 2}}
            };
            for (int i = 0; i < 4; i++) {
                int lr = pos.r + legs[i][0], lc = pos.c + legs[i][1]; // Horse leg
                if (!b.in(lr, lc) || b.at(lr, lc) != null) continue;      // Blocked horse leg
                for (int[] d : dsts[i]) {
                    int r = pos.r + d[0], c = pos.c + d[1];
                    if (!b.in(r, c) || isOwn(b, r, c)) continue;
                    out.add(new Pos(r, c));
                }
            }
            return out;
        }
    }

    /**
     * Elephant: moves diagonally two squares; cannot cross river; blocked if center is occupied
     */
    public static final class Elephant extends Piece {
        public Elephant(Side s, Pos p) {
            super(PieceType.ELEPHANT, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : DIAG1) {
                int r = pos.r + 2 * d[0], c = pos.c + 2 * d[1];
                int mr = pos.r + d[0], mc = pos.c + d[1]; // Elephant eye
                if (!b.in(r, c) || b.at(mr, mc) != null || isOwn(b, r, c)) continue;
                // Cannot cross river
                if (side == Side.RED && r <= 4) continue;
                if (side == Side.BLACK && r >= 5) continue;
                out.add(new Pos(r, c));
            }
            return out;
        }
    }

    /**
     * Advisor: moves one square diagonally within palace
     */
    public static final class Advisor extends Piece {
        public Advisor(Side s, Pos p) {
            super(PieceType.ADVISOR, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : DIAG1) {
                int r = pos.r + d[0], c = pos.c + d[1];
                if (!b.in(r, c) || isOwn(b, r, c)) continue;
                if (b.inPalace(side, r, c)) out.add(new Pos(r, c));
            }
            return out;
        }
    }

    /**
     * General: moves one square orthogonally within palace; can capture enemy general if aligned with no pieces between; cannot face each other
     */
    public static final class General extends Piece {
        public General(Side s, Pos p) {
            super(PieceType.GENERAL, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                if (!b.in(r, c) || isOwn(b, r, c)) continue;
                if (b.inPalace(side, r, c)) out.add(new Pos(r, c));
            }
            // Face-to-face capture (same column vertically with no pieces between)
            Pos opp = b.findGeneral(side.opponent());
            if (opp != null && opp.c == pos.c) {
                if (b.clearBetweenSameCol(pos.r, opp.r, pos.c)) {
                    out.add(new Pos(opp.r, opp.c));
                }
            }
            return out;
        }
    }

    /**
     * Pawn: moves forward one square before crossing river; can move sideways after crossing river; never moves backward
     */
    public static final class Pawn extends Piece {
        public Pawn(Side s, Pos p) {
            super(PieceType.PAWN, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            int fr = pos.r + side.dir, fc = pos.c; // Forward one step
            if (b.in(fr, fc) && !isOwn(b, fr, fc)) out.add(new Pos(fr, fc));
            // Can move sideways after crossing river
            boolean crossed = (side == Side.RED ? pos.r <= 4 : pos.r >= 5);
            if (crossed) {
                int[][] lr = {{0, -1}, {0, 1}};
                for (int[] d : lr) {
                    int r = pos.r, c = pos.c + d[1];
                    if (b.in(r, c) && !isOwn(b, r, c)) out.add(new Pos(r, c));
                }
            }
            return out;
        }
    }

    /* ===================== Board and Legality ===================== */

    public static final class Board {
        private final Piece[][] grid = new Piece[10][9];

        public boolean in(int r, int c) {
            return r >= 0 && r < 10 && c >= 0 && c < 9;
        }

        public Piece at(int r, int c) {
            return in(r, c) ? grid[r][c] : null;
        }

        public void set(int r, int c, Piece p) {
            if (in(r, c)) {
                grid[r][c] = p;
                if (p != null) p.pos = new Pos(r, c);
            }
        }

        /**
         * Create initial setup
         */
        public static Board initial() {
            Board b = new Board();
            // Black side (top)
            b.set(0, 0, new Rook(Side.BLACK, new Pos(0, 0)));
            b.set(0, 1, new Horse(Side.BLACK, new Pos(0, 1)));
            b.set(0, 2, new Elephant(Side.BLACK, new Pos(0, 2)));
            b.set(0, 3, new Advisor(Side.BLACK, new Pos(0, 3)));
            b.set(0, 4, new General(Side.BLACK, new Pos(0, 4)));
            b.set(0, 5, new Advisor(Side.BLACK, new Pos(0, 5)));
            b.set(0, 6, new Elephant(Side.BLACK, new Pos(0, 6)));
            b.set(0, 7, new Horse(Side.BLACK, new Pos(0, 7)));
            b.set(0, 8, new Rook(Side.BLACK, new Pos(0, 8)));
            b.set(2, 1, new Cannon(Side.BLACK, new Pos(2, 1)));
            b.set(2, 7, new Cannon(Side.BLACK, new Pos(2, 7)));
            for (int c = 0; c < 9; c += 2) b.set(3, c, new Pawn(Side.BLACK, new Pos(3, c)));

            // Red side (bottom)
            b.set(9, 0, new Rook(Side.RED, new Pos(9, 0)));
            b.set(9, 1, new Horse(Side.RED, new Pos(9, 1)));
            b.set(9, 2, new Elephant(Side.RED, new Pos(9, 2)));
            b.set(9, 3, new Advisor(Side.RED, new Pos(9, 3)));
            b.set(9, 4, new General(Side.RED, new Pos(9, 4)));
            b.set(9, 5, new Advisor(Side.RED, new Pos(9, 5)));
            b.set(9, 6, new Elephant(Side.RED, new Pos(9, 6)));
            b.set(9, 7, new Horse(Side.RED, new Pos(9, 7)));
            b.set(9, 8, new Rook(Side.RED, new Pos(9, 8)));
            b.set(7, 1, new Cannon(Side.RED, new Pos(7, 1)));
            b.set(7, 7, new Cannon(Side.RED, new Pos(7, 7)));
            for (int c = 0; c < 9; c += 2) b.set(6, c, new Pawn(Side.RED, new Pos(6, c)));
            return b;
        }

        /**
         * Check if in palace
         */
        public boolean inPalace(Side s, int r, int c) {
            if (!in(r, c)) return false;
            if (s == Side.RED) return r >= 7 && r <= 9 && c >= 3 && c <= 5;
            else return r >= 0 && r <= 2 && c >= 3 && c <= 5;
        }

        /**
         * Find general position
         */
        public Pos findGeneral(Side s) {
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++) {
                    Piece p = grid[r][c];
                    if (p != null && p.type == PieceType.GENERAL && p.side == s) return new Pos(r, c);
                }
            return null;
        }

        /**
         * Check if column is clear between two rows (excluding ends)
         */
        public boolean clearBetweenSameCol(int r1, int r2, int c) {
            int a = Math.min(r1, r2) + 1, b = Math.max(r1, r2) - 1;
            for (int r = a; r <= b; r++) if (grid[r][c] != null) return false;
            return true;
        }

        /**
         * Check if generals face each other (illegal position)
         */
        public boolean generalsFacing() {
            Pos r = findGeneral(Side.RED), k = findGeneral(Side.BLACK);
            if (r == null || k == null) return false;
            if (r.c != k.c) return false;
            return clearBetweenSameCol(r.r, k.r, r.c);
        }

        /**
         * Simulate move (without validation)
         */
        public Board makeMove(Move m) {
            Board nb = this.cloneBoard();
            Piece p = nb.at(m.from.r, m.from.c);
            nb.set(m.from.r, m.from.c, null);
            nb.set(m.to.r, m.to.c, p);
            return nb;
        }

        /**
         * Check if own side is in check
         */
        public boolean inCheck(Side side) {
            Pos g = findGeneral(side);
            if (g == null) return false;
            // Enumerate all enemy pseudo-legal moves to see if they can reach our general
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++) {
                    Piece q = grid[r][c];
                    if (q == null || q.side == side) continue;
                    for (Pos dst : q.pseudoLegalMoves(this)) {
                        if (dst.r == g.r && dst.c == g.c) return true;
                    }
                }
            // Face-to-face generals
            return generalsFacing();
        }

        /**
         * Final legal moves for a piece (filtered for self-check/face-to-face)
         */
        public List<Move> legalMovesAt(Pos from) {
            Piece p = at(from.r, from.c);
            if (p == null) return Collections.emptyList();
            List<Move> out = new ArrayList<>();
            for (Pos to : p.pseudoLegalMoves(this)) {
                Board nb = makeMove(new Move(from, to));
                if (nb.generalsFacing()) continue;
                if (nb.inCheck(p.side)) continue;
                out.add(new Move(from, to));
            }
            return out;
        }

        /**
         * Clone board
         */
        public Board cloneBoard() {
            Board b = new Board();
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++) {
                    Piece p = grid[r][c];
                    if (p == null) {
                        b.grid[r][c] = null;
                        continue;
                    }
                    // Copy by type
                    Piece np;
                    switch (p.type) {
                        case ROOK -> np = new Rook(p.side, new Pos(r, c));
                        case CANNON -> np = new Cannon(p.side, new Pos(r, c));
                        case HORSE -> np = new Horse(p.side, new Pos(r, c));
                        case ELEPHANT -> np = new Elephant(p.side, new Pos(r, c));
                        case ADVISOR -> np = new Advisor(p.side, new Pos(r, c));
                        case GENERAL -> np = new General(p.side, new Pos(r, c));
                        case PAWN -> np = new Pawn(p.side, new Pos(r, c));
                        default -> throw new IllegalStateException();
                    }
                    b.grid[r][c] = np;
                }
            return b;
        }
    }
}