// SPDX-License-Identifier: Apache-2.0
package org.strand.engine;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Talks to strandd over its init socket, one line command per connection. */
public final class StrandClient {

    /** A reply: ok/err, the text after it, and any data lines. */
    public static final class Reply {
        public final boolean ok;
        public final String message;
        public final List<String> lines;

        Reply(boolean ok, String message, List<String> lines) {
            this.ok = ok;
            this.message = message;
            this.lines = lines;
        }

        /** Data lines of the form "key value" as a map. */
        public Map<String, String> map() {
            Map<String, String> m = new LinkedHashMap<>();
            for (String l : lines) {
                int sp = l.indexOf(' ');
                if (sp > 0) m.put(l.substring(0, sp), l.substring(sp + 1));
            }
            return m;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private StrandClient() {}

    /** Blocking. Returns null when the daemon can't be reached. */
    public static Reply call(String command) {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress("strandd", LocalSocketAddress.Namespace.RESERVED));
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String first = in.readLine();
            if (first == null) return null;
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null && !line.equals(".")) lines.add(line);
            boolean ok = first.startsWith("ok");
            String msg = first.length() > 3 ? first.substring(3) : "";
            return new Reply(ok, msg, lines);
        } catch (IOException e) {
            return null;
        }
    }

    /** Runs the command off the main thread and delivers the reply (or null) on it. */
    public static void async(String command, Consumer<Reply> done) {
        EXECUTOR.execute(() -> {
            Reply r = call(command);
            if (done != null) MAIN.post(() -> done.accept(r));
        });
    }
}
