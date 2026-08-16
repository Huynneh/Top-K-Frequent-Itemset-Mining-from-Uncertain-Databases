package util;

/**
 * Đo bộ nhớ heap đang dùng (JVM), phục vụ so sánh bộ nhớ giữa các thuật toán.
 *
 * LƯU Ý QUAN TRỌNG (cần ghi trong luận văn khi trích số liệu):
 * Đây là phép đo XẤP XỈ, không phải profiler chuyên dụng (như JFR hay VisualVM).
 * Cách đo: gọi System.gc() để giảm nhiễu do rác chưa dọn, rồi lấy
 * (totalMemory - freeMemory) tại 2 thời điểm trước/sau khi chạy, hiệu số đó
 * xấp xỉ lượng bộ nhớ mà thuật toán đã cấp phát thêm. Vì GC không đảm bảo
 * chạy ngay lập tức, số liệu có thể dao động giữa các lần chạy — nên chạy
 * lặp lại nhiều lần (ví dụ 5 lần) và lấy trung bình khi đưa vào báo cáo/luận văn.
 */
public class MemoryProfiler {

    /** Gợi ý JVM dọn rác trước khi đo, để giảm nhiễu (không đảm bảo chạy ngay). */
    public static void suggestGc() {
        System.gc();
        try {
            Thread.sleep(20);
        } catch (InterruptedException ignored) {
        }
        System.gc();
    }

    /** Trả về số byte heap đang được sử dụng tại thời điểm gọi. */
    public static long usedMemoryBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) bytes = 0; // tránh hiển thị số âm do nhiễu đo đạc (GC chạy giữa chừng)
        double kb = bytes / 1024.0;
        double mb = kb / 1024.0;
        if (mb >= 1.0) {
            return String.format("%.2f MB", mb);
        }
        return String.format("%.2f KB", kb);
    }
}
