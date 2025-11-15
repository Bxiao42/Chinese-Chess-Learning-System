package com.example.xiangqi.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Service
public class EngineService {

    @Value("${engine.path:}")
    private String enginePathOnFs;           // Can be empty: prioritize file system, extract from classpath if not found

    @Value("${engine.movetime.ms:400}")
    private int moveTimeMs;

    @Value("${engine.movetime.best.ms:200}")
    private int moveTimeBestMs;

    private Process engine;
    private BufferedWriter stdin;
    private BufferedReader stdout;

    private final ExecutorService ioPool = Executors.newFixedThreadPool(2);
    private File workDir; // Temporary working directory (contains exe / nnue)

    @PostConstruct
    public void start() throws Exception {
        // 1) Prepare executable file & NNUE
        prepareBinaries();

        // 2) Start engine
        ProcessBuilder pb = new ProcessBuilder(new File(workDir, exeName()).getAbsolutePath());
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        engine = pb.start();

        stdin  = new BufferedWriter(new OutputStreamWriter(engine.getOutputStream()));
        stdout = new BufferedReader(new InputStreamReader(engine.getInputStream()));

        // 3) Enter UCI mode
        send("uci");
        waitUntilContains("uciok", Duration.ofSeconds(5));

        File nnue = new File(workDir, "pikafish.nnue");
        if (nnue.exists()) {
            send("setoption name EvalFile value " + nnue.getAbsolutePath());
        }

        send("isready");
        waitUntilContains("readyok", Duration.ofSeconds(5));

        System.out.println("Pikafish started at: " + workDir.getAbsolutePath());
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    @PreDestroy
    public void stop() {
        try { send("quit"); } catch (Exception ignored) {}
        try { if (stdin  != null) stdin.close(); } catch (Exception ignored) {}
        try { if (stdout != null) stdout.close(); } catch (Exception ignored) {}
        if (engine != null) engine.destroy();
        ioPool.shutdownNow();
        System.out.println("Pikafish stopped.");
    }

    /** Calculate best move: fen can be null, playedMoves is UCI move sequence (spaces/commas both acceptable) */
    public String bestMove(String fen, List<String> playedMoves, boolean faster) throws Exception {
        if (fen == null || fen.trim().isEmpty()) {
            send("ucinewgame");
            send("position startpos" + buildMovesSuffix(playedMoves));
        } else {
            send("ucinewgame");
            send("position fen " + fen + buildMovesSuffix(playedMoves));
        }
        int mt = faster ? Math.max(50, moveTimeBestMs) : Math.max(100, moveTimeMs);
        send("go movetime " + mt);
        return waitBestMove(Duration.ofSeconds(8));
    }

    /* ------------------ Internal utilities ------------------ */

    private void prepareBinaries() throws IOException {
        // Prioritize file system path (development phase)
        if (enginePathOnFs != null && !enginePathOnFs.trim().isEmpty()) {
            File exe = new File(enginePathOnFs);
            if (exe.exists()) {
                workDir = createWorkDir();
                Files.copy(exe.toPath(), new File(workDir, exeName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                copyIfExistsFsSibling(exe.getParentFile(), "pikafish.nnue");
                makeExecutable(new File(workDir, exeName()));
                return;
            }
        }
        // Otherwise extract from classpath (after packaging)
        workDir = createWorkDir();
        copyFromClasspath("engine/" + exeName(), new File(workDir, exeName()));
        copyFromClasspath("engine/pikafish.nnue", new File(workDir, "pikafish.nnue")); // Ignore if not exists
        makeExecutable(new File(workDir, exeName()));
    }

    private File createWorkDir() throws IOException {
        File dir = Files.createTempDirectory("pikafish_work_").toFile();
        dir.deleteOnExit();
        return dir;
    }

    private void copyIfExistsFsSibling(File folder, String name) {
        File f = new File(folder, name);
        if (f.exists()) {
            try {
                Files.copy(f.toPath(), new File(workDir, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }
    }

    private void copyFromClasspath(String cpPath, File target) throws IOException {
        ClassPathResource res = new ClassPathResource(cpPath);
        if (!res.exists()) return; // nnue might not exist
        InputStream in = null;
        try {
            in = res.getInputStream();
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private void makeExecutable(File f) {
        try { f.setExecutable(true); } catch (Exception ignored) {}
    }

    private String exeName() { return "pikafish-avx2.exe"; }

    private void send(String cmd) throws IOException {
        stdin.write(cmd);
        stdin.write("\n");
        stdin.flush();
    }

    private String waitBestMove(Duration timeout) throws Exception {
        final BlockingQueue<String> q = new ArrayBlockingQueue<>(1);
        Future<?> fu = ioPool.submit(() -> {
            String line;
            try {
                while ((line = stdout.readLine()) != null) {
                    if (line.startsWith("bestmove")) {
                        q.offer(line);
                        break;
                    }
                }
            } catch (IOException ignored) {}
        });
        String best = q.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (best == null) {
            fu.cancel(true);
            throw new TimeoutException("Engine did not return bestmove in time");
        }
        String[] sp = best.split("\\s+");
        return sp.length >= 2 ? sp[1] : "";
    }

    private void waitUntilContains(String token, Duration timeout) throws Exception {
        Future<Boolean> fu = ioPool.submit(() -> {
            String line;
            try {
                while ((line = stdout.readLine()) != null) {
                    if (line.contains(token)) return true;
                }
            } catch (IOException ignored) {}
            return false;
        });
        Boolean ok;
        try {
            ok = fu.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fu.cancel(true);
            throw e;
        }
        if (ok == null || !ok) throw new TimeoutException("Waiting for token failed: " + token);
    }

    private String buildMovesSuffix(List<String> moves) {
        if (moves == null || moves.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" moves");
        for (String m : moves) {
            if (m != null && !m.trim().isEmpty()) {
                sb.append(' ').append(m.trim());
            }
        }
        return sb.toString();
    }

    /* Convenience overload */
    public String bestMove(String fen) throws Exception {
        return bestMove(fen, new ArrayList<>(), false);
    }
}