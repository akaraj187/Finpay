import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

public class Main {
    static final Path ROOT = Paths.get(System.getenv().getOrDefault("FINPAY_PUBLIC", "public")).toAbsolutePath().normalize();
    static final Map<String, String> MIME = Map.of("html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
        "js", "application/javascript; charset=utf-8", "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon");

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Store.seed();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", Api::handle);
        server.createContext("/", Main::serveStatic);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("\n  ✦ FinPay is running → http://localhost:" + port);
        System.out.println("  Demo student: aarav@college.edu / Student@123");
        System.out.println("  Demo admin:   admin@finpay.edu  / Admin@123\n");
    }

    static void serveStatic(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        Path f = ROOT.resolve(p.substring(1)).normalize();
        if (!f.startsWith(ROOT) || !Files.isRegularFile(f)) { Api.send(ex, 404, "{\"error\":\"Not found\"}"); return; }
        String name = f.getFileName().toString(); String ext = name.substring(name.lastIndexOf('.') + 1);
        byte[] b = Files.readAllBytes(f);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
        h.set("X-Content-Type-Options", "nosniff"); h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "same-origin");
        h.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; script-src 'self' 'unsafe-inline'; img-src 'self' data:");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
