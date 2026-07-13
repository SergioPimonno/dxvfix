package dxvfix.gui;

import dxvfix.i18n.Messages;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Font;
import java.awt.FlowLayout;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

/**
 * Small always-on CPU / GPU / RAM readout pinned to the main window's bottom-right corner -- lets
 * the user visually confirm the app isn't competing with Resolume Arena for system resources while
 * a scan, repair, or show-monitoring session is running (the same concern {@code ShowWatcher} and
 * its "System load" Help topic already address from the app's own side; this just makes it
 * observable instead of only documented).
 * <p>
 * CPU and RAM come from the JVM's {@code com.sun.management.OperatingSystemMXBean} extension --
 * system-wide (not just this process), and effectively free to read since no process is spawned.
 * GPU has no equivalent standard Java API, so it's approximated by summing the Windows "GPU
 * Engine" performance counter's per-engine "Utilization Percentage" via a one-shot {@code
 * typeperf} call on a background thread; if that's unavailable (older Windows, no GPU counters
 * registered, non-Windows), the GPU readout just shows "--" rather than failing the whole panel.
 * The {@code typeperf} call takes about a second to return (rate counters need two samples), so
 * it's polled less often than CPU/RAM to keep the background thread's own footprint negligible.
 */
final class SystemMonitorPanel extends JPanel {

    private static final long POLL_INTERVAL_MS = 3000;
    private static final int GPU_POLL_EVERY_N_TICKS = 3; // ~9s between GPU samples

    private final JLabel cpuLabel = new JLabel();
    private final JLabel gpuLabel = new JLabel();
    private final JLabel ramLabel = new JLabel();

    private final com.sun.management.OperatingSystemMXBean osBean =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    SystemMonitorPanel() {
        super(new FlowLayout(FlowLayout.RIGHT, 14, 2));

        Font small = cpuLabel.getFont().deriveFont(Font.PLAIN, cpuLabel.getFont().getSize2D() - 1f);
        cpuLabel.setFont(small);
        gpuLabel.setFont(small);
        ramLabel.setFont(small);

        add(cpuLabel);
        add(gpuLabel);
        add(ramLabel);
        applyLabels(0, null, 0);

        Thread poller = new Thread(this::pollLoop, "system-monitor");
        poller.setDaemon(true);
        poller.setPriority(Thread.MIN_PRIORITY);
        poller.start();
    }

    private void pollLoop() {
        Integer lastGpu = null;
        long tick = 0;
        while (true) {
            double cpu = readCpuPercent();
            double ram = readRamUsedPercent();
            if (tick % GPU_POLL_EVERY_N_TICKS == 0) {
                lastGpu = readGpuPercent();
            }
            Integer gpu = lastGpu;
            tick++;

            SwingUtilities.invokeLater(() -> applyLabels(cpu, gpu, ram));

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void applyLabels(double cpuPercent, Integer gpuPercent, double ramPercent) {
        cpuLabel.setText(Messages.get("monitor.cpu", formatPercent(cpuPercent)));
        gpuLabel.setText(Messages.get("monitor.gpu", gpuPercent != null ? gpuPercent + "%" : "--"));
        ramLabel.setText(Messages.get("monitor.ram", formatPercent(ramPercent)));
    }

    private static String formatPercent(double percent) {
        return Math.round(percent) + "%";
    }

    private double readCpuPercent() {
        double v = osBean.getCpuLoad(); // 0.0-1.0, or negative if not yet available
        return v < 0 ? 0 : v * 100;
    }

    private double readRamUsedPercent() {
        long total = osBean.getTotalMemorySize();
        long free = osBean.getFreeMemorySize();
        if (total <= 0) return 0;
        return (double) (total - free) / total * 100;
    }

    /**
     * Windows has no single "GPU usage" counter comparable to Task Manager's own (its exact
     * aggregation algorithm isn't public); this sums the "Utilization Percentage" of every "GPU
     * Engine" instance as a reasonable approximation, clamped to 100 since a busy GPU can report
     * several simultaneously-active engines each near 100%.
     */
    private Integer readGpuPercent() {
        try {
            Process p = new ProcessBuilder("typeperf", "-sc", "1", "\\GPU Engine(*)\\Utilization Percentage")
                    .redirectErrorStream(true).start();
            double sum = 0;
            boolean sawDataLine = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lineIndex = 0;
                while ((line = reader.readLine()) != null) {
                    if (lineIndex > 0 && line.startsWith("\"")) { // skip the PDH-CSV header row
                        sawDataLine = true;
                        String[] cells = line.split(",");
                        for (int i = 1; i < cells.length; i++) { // cell 0 is the sample timestamp
                            try {
                                sum += Double.parseDouble(cells[i].replace("\"", ""));
                            } catch (NumberFormatException ignored) {
                                // Non-numeric cell (e.g. "-1" for a counter that errored) -- skip it.
                            }
                        }
                    }
                    lineIndex++;
                }
            }
            p.waitFor();
            return sawDataLine ? (int) Math.round(Math.min(100, sum)) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
