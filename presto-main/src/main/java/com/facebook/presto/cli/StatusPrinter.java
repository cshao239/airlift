package com.facebook.presto.cli;

import com.facebook.presto.execution.ExecutionStats;
import com.facebook.presto.execution.PageBufferInfo;
import com.facebook.presto.execution.QueryInfo;
import com.facebook.presto.execution.QueryState;
import com.facebook.presto.execution.QueryStats;
import com.facebook.presto.execution.StageInfo;
import com.facebook.presto.execution.TaskInfo;
import com.facebook.presto.server.HttpQueryClient;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Uninterruptibles;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.Ansi.Erase;

import java.io.PrintStream;
import java.math.RoundingMode;
import java.net.URI;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.facebook.presto.operator.OutputProcessor.OutputStats;
import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fusesource.jansi.internal.CLibrary.STDOUT_FILENO;
import static org.fusesource.jansi.internal.CLibrary.isatty;

public class StatusPrinter
{
    private static final Logger log = Logger.get(StatusPrinter.class);

    private final long start = System.nanoTime();
    private final HttpQueryClient queryClient;
    private final PrintStream out;
    private int lines;

    public StatusPrinter(HttpQueryClient queryClient, PrintStream out)
    {
        this.queryClient = queryClient;
        this.out = out;
    }

/*

Query 4: RUNNING, 1 nodes, 16.1s elapsed
Splits:  707 total,  562 pending,   66 running,   79 done
CPU user:  38.4s 2.14MB/s total,  38.4s 2.14MB/s per node
CPU wall:  16.1s 5.12MB/s total,  16.1s 5.12MB/s per node

     STAGES   ROWS  ROWS/s  BYTES  BYTES/s   PEND    RUN   DONE
0.........R  13.8M    336K  1.99G    49.5M      0      1    706
  1.......R   666K   41.5K  82.1M    5.12M    563     65     79
    2.....R  4.58M    234K   620M    31.6M    406     65    236

 */

    public void printInitialStatusUpdates()
    {
        long lastPrint = System.nanoTime();
        try {
            while (true) {
                try {
                    QueryInfo queryInfo = queryClient.getQueryInfo(false);

                    // if query is no longer running, finish
                    if ((queryInfo == null) || queryInfo.getState().isDone()) {
                        return;
                    }

                    // check if there is there is pending output
                    if (queryInfo.getOutputStage() != null) {
                        List<TaskInfo> outStage = queryInfo.getOutputStage().getTasks();
                        for (TaskInfo outputTask : outStage) {
                            for (PageBufferInfo outputBuffer : outputTask.getOutputBuffers()) {
                                if (outputBuffer.getBufferedPages() > 0) {
                                    return;
                                }
                            }
                        }
                    }

                    if (Duration.nanosSince(lastPrint).convertTo(SECONDS) >= 0.5) {
                        repositionCursor();
                        printQueryInfo(queryInfo);
                        lastPrint = System.nanoTime();
                    }
                    Uninterruptibles.sleepUninterruptibly(100, MILLISECONDS);
                }
                catch (Exception e) {
                    log.debug(e, "error printing status");
                }
            }
        }
        finally {
            resetScreen();
        }
    }

    public void printFinalInfo(OutputStats stats)
    {
        Duration wallTime = Duration.nanosSince(start);

        QueryInfo queryInfo = queryClient.getQueryInfo(true);
        QueryStats queryStats = queryInfo.getQueryStats();

        StageInfo outputStage = queryInfo.getOutputStage();

        // only include input (leaf) stages
        ExecutionStats inputExecutionStats = new ExecutionStats();
        sumStats(outputStage, inputExecutionStats, true);

        // sum all stats (used for global stats like cpu time)
        ExecutionStats globalExecutionStats = new ExecutionStats();
        sumStats(outputStage, globalExecutionStats, false);

        int nodes = uniqueNodes(outputStage).size();

        // blank line
        out.println();

        // Query 12, FINISHED, 1 node
        String querySummary = String.format("Query %s, %s, %,d %s",
                queryInfo.getQueryId(),
                queryInfo.getState(),
                nodes,
                pluralize("node", nodes));
        out.println(querySummary);

        // Splits: 1000 total, 842 done (84.20%)
        String splitsSummary = String.format("Splits: %,d total, %,d done (%.2f%%)",
                globalExecutionStats.getSplits(),
                globalExecutionStats.getCompletedSplits(),
                Math.min(100, globalExecutionStats.getCompletedSplits() * 100.0 / globalExecutionStats.getSplits()));
        out.println(splitsSummary);


        if (queryClient.isDebug()) {
            // CPU Time: 565.2s total,   26K rows/s, 3.85MB/s
            Duration cpuTime = new Duration(globalExecutionStats.getSplitCpuTime(), MILLISECONDS);
            String cpuTimeSummary = String.format("CPU Time: %.1fs total, %5s rows/s, %8s, %d%% active",
                    cpuTime.convertTo(SECONDS),
                    formatCountRate(inputExecutionStats.getCompletedPositionCount(), cpuTime, false),
                    formatDataRate(inputExecutionStats.getCompletedDataSize(), cpuTime, true),
                    (int) (globalExecutionStats.getSplitCpuTime() * 100.0 / globalExecutionStats.getSplitWallTime()));
            out.println(cpuTimeSummary);

            out.println(String.format("Parallelism: %.1f", cpuTime.toMillis() / wallTime.toMillis()));
        }

        // 0:32 [2.12GB, 15M rows] [67MB/s, 463K rows/s]
        String statsLine = String.format("%s [%s rows, %s] [%s rows/s, %s]",
                formatTime(wallTime),
                formatCount(inputExecutionStats.getCompletedPositionCount()),
                formatDataSize(inputExecutionStats.getCompletedDataSize(), true),
                formatCountRate(inputExecutionStats.getInputPositionCount(), wallTime, false),
                formatDataRate(inputExecutionStats.getCompletedDataSize(), wallTime, true));

        out.println(statsLine);

        // blank line
        out.println();
    }

    private void printQueryInfo(QueryInfo queryInfo)
    {
        Duration wallTime = Duration.nanosSince(start);

        StageInfo outputStage = queryInfo.getOutputStage();

        // only include input (leaf) stages
        ExecutionStats inputExecutionStats = new ExecutionStats();
        sumStats(outputStage, inputExecutionStats, true);

        ExecutionStats globalExecutionStats = new ExecutionStats();
        sumStats(outputStage, globalExecutionStats, false);

        int nodes = uniqueNodes(outputStage).size();
        if (REAL_TERMINAL) {
            // blank line
            reprintLine("");

            // Query 10, RUNNING, 1 node, 778 splits
            String querySummary = String.format("Query %s, %s, %,d %s, %,d splits",
                    queryInfo.getQueryId(),
                    queryInfo.getState(),
                    nodes,
                    pluralize("node", nodes),
                    globalExecutionStats.getSplits());
            reprintLine(querySummary);

            if (queryInfo.getState() == QueryState.PLANNING) {
                return;
            }

            if (queryClient.isDebug()) {
                // Splits:   620 pending, 34 running, 124 done
                String splitsSummary = String.format("Splits:   %,d pending, %,d running, %,d done",
                        max(0, globalExecutionStats.getSplits() - globalExecutionStats.getStartedSplits()),
                        max(0, globalExecutionStats.getStartedSplits() - globalExecutionStats.getCompletedSplits()),
                        globalExecutionStats.getCompletedSplits());
                reprintLine(splitsSummary);

                // CPU Time: 56.5s total, 36.4K rows/s, 4.44MB/s, 60% active
                Duration cpuTime = new Duration(globalExecutionStats.getSplitCpuTime(), MILLISECONDS);
                String cpuTimeSummary = String.format("CPU Time: %.1fs total, %5s rows/s, %8s, %d%% active",
                        cpuTime.convertTo(SECONDS),
                        formatCountRate(inputExecutionStats.getCompletedPositionCount(), cpuTime, false),
                        formatDataRate(inputExecutionStats.getCompletedDataSize(), cpuTime, true),
                        (int) (globalExecutionStats.getSplitCpuTime() * 100.0 / globalExecutionStats.getSplitWallTime()));
                reprintLine(cpuTimeSummary);

                reprintLine(String.format("Parallelism: %.1f", cpuTime.toMillis() / wallTime.toMillis()));
            }

            String progressBar = formatProgressBar(42, // 42 is to keep the total progress line width <= 100 chars
                    globalExecutionStats.getCompletedSplits(),
                    max(0, globalExecutionStats.getStartedSplits() - globalExecutionStats.getCompletedSplits()),
                    globalExecutionStats.getSplits());

            // 0:17 [ 103MB,  802K rows] [5.74MB/s, 44.9K rows/s] [=====>>                                   ] 10%
            String progressLine = String.format("%s [%5s rows, %6s] [%5s rows/s, %8s] [%s] %d%%",
                    formatTime(wallTime),
                    formatCount(inputExecutionStats.getCompletedPositionCount()),
                    formatDataSize(inputExecutionStats.getCompletedDataSize(), true),
                    formatCountRate(inputExecutionStats.getInputPositionCount(), wallTime, false),
                    formatDataRate(inputExecutionStats.getCompletedDataSize(), wallTime, true),
                    progressBar,
                    // cap progress at 99%, otherwise it looks weird when the query is still running and it says 100%
                    Math.min(99, (int) (globalExecutionStats.getCompletedSplits() * 100.0 / globalExecutionStats.getSplits())));

            reprintLine(progressLine);

            // todo Mem: 1949M shared, 7594M private

            // blank line
            reprintLine("");

            // STAGE  S    ROWS    RPS  BYTES    BPS   PEND    RUN   DONE
            String stagesHeader = String.format("%10s%1s  %5s  %6s  %5s  %7s  %5s  %5s  %5s",
                    "STAGE",
                    "S",
                    "ROWS",
                    "ROWS/s",
                    "BYTES",
                    "BYTES/s",
                    "PEND",
                    "RUN",
                    "DONE");
            reprintLine(stagesHeader);

            printStageTree(outputStage, "");
        }
        else {
            // Query 31 [S] i[2.7M 67.3MB 62.7MBps] o[35 6.1KB 1KBps] splits[252/16/380]
            String querySummary = String.format("Query %s [%s] i[%s %s %s] o[%s %s %s] splits[%,d/%,d/%,d]",
                    queryInfo.getQueryId(),
                    queryInfo.getState().toString().charAt(0),

                    formatCount(globalExecutionStats.getInputPositionCount()),
                    formatDataSize(globalExecutionStats.getInputDataSize(), false),
                    formatDataRate(globalExecutionStats.getCompletedDataSize(), wallTime, false),

                    formatCount(globalExecutionStats.getOutputPositionCount()),
                    formatDataSize(globalExecutionStats.getOutputDataSize(), false),
                    formatDataRate(globalExecutionStats.getOutputDataSize(), wallTime, false),

                    max(0, globalExecutionStats.getSplits() - globalExecutionStats.getStartedSplits()),
                    max(0, globalExecutionStats.getStartedSplits() - globalExecutionStats.getCompletedSplits()),
                    globalExecutionStats.getCompletedSplits());
            reprintLine(querySummary);
        }
    }

    private void printStageTree(StageInfo stage, String indent)
    {
        Duration elapsedTime = Duration.nanosSince(start);

        ExecutionStats executionStats = new ExecutionStats();
        sumTaskStats(stage, executionStats);

        // STAGE  S    ROWS  ROWS/s  BYTES  BYTES/s   PEND    RUN   DONE
        // 0......Q     26M   9077M  9993G    9077M  9077M  9077M  9077M
        //   2....R     17K    627M   673M     627M   627M   627M   627M
        //     3..C     999    627M   673M     627M   627M   627M   627M
        //   4....R     26M    627M   673T     627M   627M   627M   627M
        //     5..F     29T    627M   673M     627M   627M   627M   627M

        // todo this is a total hack
        String id = stage.getStageId().substring(stage.getQueryId().length() + 1);
        StringBuilder nameBuilder = new StringBuilder();
        nameBuilder.append(indent).append(id);
        while (nameBuilder.length() < 10) {
            nameBuilder.append('.');
        }

        String stageSummary = String.format("%10s%1s  %5s  %6s  %5s  %7s  %5s  %5s  %5s",
                nameBuilder.toString(),
                stage.getState().toString().charAt(0),

                formatCount(executionStats.getInputPositionCount()),
                formatCountRate(executionStats.getInputPositionCount(), elapsedTime, false),

                formatDataSize(executionStats.getInputDataSize(), false),
                formatDataRate(executionStats.getCompletedDataSize(), elapsedTime, false),

                max(0, executionStats.getSplits() - executionStats.getStartedSplits()),
                max(0, executionStats.getStartedSplits() - executionStats.getCompletedSplits()),
                executionStats.getCompletedSplits());
        reprintLine(stageSummary);

        for (StageInfo subStage : stage.getSubStages()) {
            printStageTree(subStage, indent + "  ");
        }
    }

    private static void sumStats(StageInfo stageInfo, ExecutionStats executionStats, boolean sumLeafOnly)
    {
        if (stageInfo == null) {
            return;
        }
        if (!sumLeafOnly || stageInfo.getSubStages().isEmpty()) {
            sumTaskStats(stageInfo, executionStats);
        }
        for (StageInfo subStage : stageInfo.getSubStages()) {
            sumStats(subStage, executionStats, sumLeafOnly);
        }
    }

    private static void sumTaskStats(StageInfo stageInfo, ExecutionStats executionStats)
    {
        for (TaskInfo task : stageInfo.getTasks()) {
            executionStats.add(task.getStats());
        }
    }

    private static Set<String> uniqueNodes(StageInfo stageInfo)
    {
        if (stageInfo == null) {
            return ImmutableSet.of();
        }
        ImmutableSet.Builder<String> nodes = ImmutableSet.builder();
        for (TaskInfo task : stageInfo.getTasks()) {
            // todo add nodeId to TaskInfo
            URI uri = task.getSelf();
            nodes.add(uri.getHost() + ":" + uri.getPort());
        }

        for (StageInfo subStage : stageInfo.getSubStages()) {
            nodes.addAll(uniqueNodes(subStage));
        }
        return nodes.build();
    }

    private static String formatCount(long count)
    {
        double fractional = count;
        String unit = "";
        if (fractional > 1000) {
            fractional /= 1000;
            unit = "K";
        }
        if (fractional > 1000) {
            fractional /= 1000;
            unit = "M";
        }
        if (fractional > 1000) {
            fractional /= 1000;
            unit = "B";
        }
        if (fractional > 1000) {
            fractional /= 1000;
            unit = "T";
        }
        if (fractional > 1000) {
            fractional /= 1000;
            unit = "Q";
        }

        DecimalFormat format = getFormat(fractional);
        return String.format("%s%s", format.format(fractional), unit);
    }

    private static DecimalFormat getFormat(double value)
    {
        DecimalFormat format;
        if (value < 10) {
            // show up to two decimals to get 3 significant digits
            format = new DecimalFormat("#.##");
        }
        else if (value < 100) {
            // show up to one decimal to get 3 significant digits
            format = new DecimalFormat("#.#");
        }
        else {
            // show no decimals -- we have enough digits in the integer part
            format = new DecimalFormat("#");
        }

        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }

    private static String formatCountRate(double count, Duration duration, boolean longForm)
    {
        double rate = count / duration.convertTo(SECONDS);
        if (Double.isNaN(rate) || Double.isInfinite(rate)) {
            rate = 0;
        }

        String rateString = formatCount((long) rate);
        if (longForm) {
            if (rateString.endsWith(" ")) {
                rateString = rateString.substring(0, rateString.length() - 1);
            }
            rateString += "/s";
        }
        return rateString;
    }

    private static String formatDataSize(long size, boolean longForm)
    {
        double fractional = size;
        String unit = null;
        if (fractional >= 1024) {
            fractional /= 1024;
            unit = "K";
        }
        if (fractional >= 1024) {
            fractional /= 1024;
            unit = "M";
        }
        if (fractional >= 1024) {
            fractional /= 1024;
            unit = "G";
        }
        if (fractional >= 1024) {
            fractional /= 1024;
            unit = "T";
        }
        if (fractional >= 1024) {
            fractional /= 1024;
            unit = "P";
        }

        if (unit == null) {
            unit = "B";
        }
        else if (longForm) {
            unit = unit + "B";
        }

        DecimalFormat format = getFormat(fractional);
        return String.format("%s%s", format.format(fractional), unit);
    }

    private static String formatDataRate(double dataSize, Duration duration, boolean longForm)
    {
        double rate = dataSize / duration.convertTo(SECONDS);
        if (Double.isNaN(rate) || Double.isInfinite(rate)) {
            rate = 0;
        }

        String rateString = formatDataSize((long) rate, false);
        if (longForm) {
            if (!rateString.endsWith("B")) {
                rateString += "B";
            }
            rateString += "/s";
        }
        return rateString;
    }

    private void reprintLine(String line)
    {
        if (REAL_TERMINAL) {
            out.print(Ansi.ansi().eraseLine(Erase.ALL).a(line).a('\n').toString());
        }
        else {
            out.print('\r' + line);
        }
        out.flush();
        lines++;
    }

    private void repositionCursor()
    {
        if (lines > 0) {
            if (REAL_TERMINAL) {
                out.print(Ansi.ansi().cursorUp(lines).toString());
            }
            else {
                out.print('\r');
            }
            out.flush();
            lines = 0;
        }
    }

    private void resetScreen()
    {
        if (lines > 0) {
            if (REAL_TERMINAL) {
                out.print(Ansi.ansi().cursorUp(lines).eraseScreen(Erase.FORWARD).toString());
            }
            else {
                out.print('\r');
            }
            out.flush();
            lines = 0;
        }
    }

    public static final boolean REAL_TERMINAL = isRealTerminal();

    private static boolean isRealTerminal()
    {
        // If the jansi.passthrough property is set, then don't interpret
        // any of the ansi sequences.
        if (Boolean.parseBoolean(System.getProperty("jansi.passthrough"))) {
            return true;
        }

        // If the jansi.strip property is set, then we just strip the
        // the ansi escapes.
        if (Boolean.parseBoolean(System.getProperty("jansi.strip"))) {
            return false;
        }

        String os = System.getProperty("os.name");
        if (os.startsWith("Windows")) {
            // We could support this, but we'd need a windows box
            return true;
        }

        // We must be on some unix variant..
        try {
            // check if standard out is a terminal
            int rc = isatty(STDOUT_FILENO);
            if (rc == 0) {
                return false;
            }
        }
        catch (NoClassDefFoundError | UnsatisfiedLinkError ignore) {
            // These errors happen if the JNI lib is not available for your platform.
        }
        return true;
    }

    private static String pluralize(String word, int count)
    {
        if (count != 1) {
            return word + "s";
        }
        return word;
    }

    private static String formatTime(Duration duration)
    {
        int totalSeconds = (int) duration.convertTo(TimeUnit.SECONDS);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;

        return String.format("%s:%02d", minutes, seconds);
    }

    private static String formatProgressBar(int width, int complete, int running, int total)
    {
        if (total == 0) {
            return Strings.repeat(" ", width);
        }

        int pending = Math.max(0, total - complete - running);

        // compute nominal lengths
        int completeLength = Math.min(width, ceil(complete * width, total));
        int pendingLength = Math.min(width, ceil(pending * width, total));

        // leave space for at least one ">" as long as running is > 0
        int minRunningLength = running > 0 ? 1 : 0;
        int runningLength = Math.max(Math.min(width, ceil(running * width, total)), minRunningLength);

        // adjust to fix rounding errors
        if (completeLength + runningLength + pendingLength != width && pending > 0) {
            // sacrifice "pending" if we're over the max width
            pendingLength = Math.max(0, width - completeLength - runningLength);
        }
        if (completeLength + runningLength + pendingLength != width) {
            // then, sacrifice "running"
            runningLength = Math.max(minRunningLength, width - completeLength - pendingLength);
        }
        if (completeLength + runningLength + pendingLength > width && complete > 0) {
            // finally, sacrifice "complete" if we're still over the limit
            completeLength = Math.max(0, width - runningLength - pendingLength);
        }

        Preconditions.checkState(completeLength + runningLength + pendingLength == width,
                "Expected completeLength (%s) + runningLength (%s) + pendingLength (%s) == width (%s), was %s for complete = %s, running = %s, total = %s",
                completeLength, runningLength, pendingLength, width, completeLength + runningLength + pendingLength, complete, running, total);

        return Strings.repeat("=", completeLength) + Strings.repeat(">", runningLength) + Strings.repeat(" ", pendingLength);
    }

    /**
     * Ceiling of integer division
     */
    private static int ceil(int dividend, int divisor)
    {
        return (dividend + divisor - 1) / divisor;
    }
}