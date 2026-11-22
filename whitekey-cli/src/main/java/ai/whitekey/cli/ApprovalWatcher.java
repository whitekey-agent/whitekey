package ai.whitekey.cli;

import ai.whitekey.api.dto.Action;
import ai.whitekey.api.dto.HttpAction;
import ai.whitekey.api.dto.RuleEffect;
import ai.whitekey.cli.client.ApiClient;
import ai.whitekey.cli.client.CliConfigDefaults;
import ai.whitekey.cli.client.CliException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Objects;

/**
 * Interactive queue watcher that presents pending actions oldest-first,
 * one at a time, using a pointer menu for immediate approval or rejection.
 */
final class ApprovalWatcher {

    enum Choice {
        APPROVE("Approve", RuleEffect.ALLOW, false),
        APPROVE_AND_SAVE("Approve and save as rule", RuleEffect.ALLOW, true),
        REJECT("Reject", RuleEffect.DENY, false);

        final String label;
        final RuleEffect decision;
        final boolean saveRule;

        Choice(String label, RuleEffect decision, boolean saveRule) {
            this.label = label;
            this.decision = decision;
            this.saveRule = saveRule;
        }
    }

    interface Prompt {
        /**
         * Displays the 3-option pointer menu for {@code current} and returns the chosen action,
         * {@code null} if the user quits, or {@link PromptResult#stale()} if the action ceased to be pending.
         */
        PromptResult select(Action current) throws IOException;

        /**
         * Waits for the poll interval between idle queue checks. Returns {@code false} if the user quit.
         */
        boolean waitForNextPoll() throws IOException;
    }

    record PromptResult(Choice choice, boolean quit, boolean stale) {
        static PromptResult selected(Choice choice) {
            return new PromptResult(choice, false, false);
        }

        static PromptResult quitSignal() {
            return new PromptResult(null, true, false);
        }

        static PromptResult staleSignal() {
            return new PromptResult(null, false, true);
        }
    }

    public static final String DEFAULT_POLL_INTERVAL_MS = "300";
    public static final String ENV_BODY_PREVIEW_CHARS = "WHITEKEY_BODY_PREVIEW_CHARS";

    private static final int ESC_SEQUENCE_TIMEOUT_MS = 30;
    private static final int DEFAULT_MAX_BODY_PREVIEW_CHARS = 160;
    private static final Choice[] OPTIONS = Choice.values();

    private final ApiClient apiClient;
    private final PrintWriter out;
    private final int pollIntervalMs;
    private final int maxBodyPreviewChars;
    private boolean ansiEnabled = true;

    ApprovalWatcher(ApiClient apiClient, PrintWriter out, int pollIntervalMs) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.out = Objects.requireNonNull(out, "out");
        this.pollIntervalMs = pollIntervalMs;
        this.maxBodyPreviewChars = CliConfigDefaults.resolveInt(
                ENV_BODY_PREVIEW_CHARS, DEFAULT_MAX_BODY_PREVIEW_CHARS);
    }

    static void watch(ApiClient apiClient, int pollIntervalMs) {
        new ApprovalWatcher(apiClient, new PrintWriter(System.out, true), pollIntervalMs)
                .watchInteractive();
    }

    void watchInteractive() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            boolean dumb = terminal.getType() != null && terminal.getType().startsWith("dumb");
            this.ansiEnabled = !dumb;
            if (dumb) {
                BufferedReader lineReader = new BufferedReader(new InputStreamReader(terminal.input()));
                watchLoop(new LinePrompt(lineReader));
            } else {
                watchLoop(new JLinePointerPrompt(terminal));
            }
        } catch (IOException e) {
            throw new CliException("Terminal error: " + e.getMessage());
        }
    }

    void watchLoop(Prompt prompt) throws IOException {
        boolean waitingMessageShown = false;
        while (true) {
            List<Action> pending = apiClient.getPendingApprovals();
            if (pending == null || pending.isEmpty()) {
                if (!waitingMessageShown) {
                    out.println("Waiting for incoming actions...");
                    out.flush();
                    waitingMessageShown = true;
                }
                if (!prompt.waitForNextPoll()) {
                    clearLines(1);
                    return;
                }
                continue;
            }

            if (waitingMessageShown) {
                clearLines(1);
                waitingMessageShown = false;
            }

            Action current = pending.get(0);
            int cardLines = printActionCard(current, pending.size());

            PromptResult result = prompt.select(current);
            clearLines(cardLines);

            if (result.quit()) {
                return;
            }
            if (result.stale()) {
                continue;
            }

            Choice choice = result.choice();
            try {
                apiClient.resolveApproval(current.id(), choice.decision.name(), choice.saveRule);
            } catch (CliException ex) {
                if (!isNoLongerPendingError(ex)) {
                    throw ex;
                }
            }
        }
    }

    private int printActionCard(Action action, int totalWaiting) {
        int lines = 1;
        String queuePrefix = totalWaiting > 1 ? "[" + totalWaiting + " pending] " : "";
        if (action instanceof HttpAction ha) {
            String method = ha.method() != null ? ha.method() : "HTTP";
            out.printf("%s%s %s%n", queuePrefix, method, formatHttpTarget(ha));
            if (ha.body() != null && !ha.body().isBlank()) {
                String compactBody = ha.body().replaceAll("\\s+", " ").trim();
                if (maxBodyPreviewChars > 0 && compactBody.length() > maxBodyPreviewChars) {
                    compactBody = compactBody.substring(0, maxBodyPreviewChars) + "...";
                }
                out.printf("Body: %s%n", compactBody);
                lines++;
            }
        } else {
            out.printf("%s[%s] %s%n", queuePrefix, action.type(), action.id());
        }
        out.flush();
        return lines;
    }

    private void clearLines(int count) {
        if (!ansiEnabled || count <= 0) {
            return;
        }
        out.print("\u001B[" + count + "A\r\u001B[J");
        out.flush();
    }

    private static String formatHttpTarget(HttpAction ha) {
        if (ha.uri() != null && !ha.uri().isBlank()) {
            return ha.uri();
        }
        String host = ha.host() != null ? ha.host() : "";
        String port = ha.port() != null ? ":" + ha.port() : "";
        String path = ha.path() != null ? ha.path() : "";
        return host + port + path;
    }

    private boolean sleepPollInterval() {
        try {
            Thread.sleep(pollIntervalMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isNoLongerPendingError(CliException ex) {
        return ex.statusCode() == HttpURLConnection.HTTP_CONFLICT
                || ex.statusCode() == HttpURLConnection.HTTP_NOT_FOUND;
    }

    private static boolean isQuitKey(int ch) {
        return ch == -1 || ch == 3 || ch == 'q' || ch == 'Q';
    }

    private final class JLinePointerPrompt implements Prompt {
        private final Terminal terminal;

        JLinePointerPrompt(Terminal terminal) {
            this.terminal = terminal;
        }

        @Override
        public boolean waitForNextPoll() throws IOException {
            Attributes saved = terminal.enterRawMode();
            try {
                int ch = terminal.reader().read(pollIntervalMs);
                return !isQuitKey(ch);
            } finally {
                terminal.setAttributes(saved);
            }
        }

        @Override
        public PromptResult select(Action current) throws IOException {
            int selected = 0;
            renderMenu(selected, false);
            Attributes saved = terminal.enterRawMode();
            try {
                NonBlockingReader reader = terminal.reader();
                while (true) {
                    int ch = reader.read(pollIntervalMs);
                    if (ch == NonBlockingReader.READ_EXPIRED) {
                        if (!isStillPending(current.id())) {
                            clearLines(OPTIONS.length);
                            return PromptResult.staleSignal();
                        }
                        continue;
                    }
                    if (isQuitKey(ch)) {
                        clearLines(OPTIONS.length);
                        return PromptResult.quitSignal();
                    }
                    if (ch == '\r' || ch == '\n') {
                        clearLines(OPTIONS.length);
                        return PromptResult.selected(OPTIONS[selected]);
                    }

                    int next = handleNavigationKey(ch, selected, reader);
                    if (next != selected) {
                        selected = next;
                        renderMenu(selected, true);
                    }
                }
            } finally {
                terminal.setAttributes(saved);
            }
        }

        private int handleNavigationKey(int ch, int selected, NonBlockingReader reader) throws IOException {
            if (ch == 27) { // ESC sequence for arrow keys
                int second = reader.read(ESC_SEQUENCE_TIMEOUT_MS);
                if (second == '[' || second == 'O') {
                    int third = reader.read(ESC_SEQUENCE_TIMEOUT_MS);
                    if (third == 'A') {
                        return (selected - 1 + OPTIONS.length) % OPTIONS.length;
                    }
                    if (third == 'B') {
                        return (selected + 1) % OPTIONS.length;
                    }
                }
                return selected;
            }
            if (ch == 'k' || ch == 'K' || ch == 'w' || ch == 'W') {
                return (selected - 1 + OPTIONS.length) % OPTIONS.length;
            }
            if (ch == 'j' || ch == 'J' || ch == 's' || ch == 'S') {
                return (selected + 1) % OPTIONS.length;
            }
            if (ch >= '1' && ch < '1' + OPTIONS.length) {
                return ch - '1';
            }
            return selected;
        }

        private boolean isStillPending(String actionId) {
            List<Action> pending = apiClient.getPendingApprovals();
            return pending != null && pending.stream().anyMatch(a -> actionId.equals(a.id()));
        }

        private void renderMenu(int selected, boolean redraw) {
            if (redraw) {
                out.print("\u001B[" + OPTIONS.length + "A\r");
            }
            for (int i = 0; i < OPTIONS.length; i++) {
                String prefix = (i == selected) ? "> " : "  ";
                if (redraw) {
                    out.print("\u001B[2K");
                }
                out.printf("%s%d. %s%n", prefix, i + 1, OPTIONS[i].label);
            }
            out.flush();
        }
    }

    private final class LinePrompt implements Prompt {
        private final BufferedReader reader;

        LinePrompt(BufferedReader reader) {
            this.reader = reader;
        }

        @Override
        public boolean waitForNextPoll() {
            return sleepPollInterval();
        }

        @Override
        public PromptResult select(Action current) throws IOException {
            for (int i = 0; i < OPTIONS.length; i++) {
                out.printf("  %d. %s%n", i + 1, OPTIONS[i].label);
            }
            while (true) {
                out.print("Select [1-" + OPTIONS.length + ", q to quit]: ");
                out.flush();
                String line = reader.readLine();
                if (line == null) {
                    return PromptResult.quitSignal();
                }
                String trimmed = line.trim();
                if ("q".equalsIgnoreCase(trimmed)) {
                    return PromptResult.quitSignal();
                }
                if (trimmed.length() == 1) {
                    int idx = trimmed.charAt(0) - '1';
                    if (idx >= 0 && idx < OPTIONS.length) {
                        return PromptResult.selected(OPTIONS[idx]);
                    }
                }
                out.println("Invalid choice. Enter 1-" + OPTIONS.length + ", or q.");
            }
        }
    }
}
