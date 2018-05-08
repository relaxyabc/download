import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author wanna
 * @createTime 2018-5-6
 * @since 1.8 Stream API
 */
public class Download {

    private static final String UTF8 = "UTF-8";

    private static final String CHARSET = "Charset";

    private String filePath;

    private String downloadFileURL;

    private int threadCount;

    // 下载的文件
    private File downloadFile;

    // 用来存储下载目标文件大小(用来计算当前下载完成百分比)
    private long fileSize = 0l;

    // 用来计算当前已完成多少
    private List<DownloadTask> downloadTasks = new ArrayList<>();

    /**
     * threadCount 为系统核心数
     *
     * @param filePath filePath
     * @param downloadFileURL  downloadFileURL
     */
    public Download(String filePath, String downloadFileURL) {
        this(filePath, downloadFileURL, Runtime.getRuntime().availableProcessors());
    }

    /**
     * @param filePath filePath
     * @param downloadFileURL  downloadFileURL
     * @param threadCount      threadCount
     */
    public Download(String filePath, String downloadFileURL, int threadCount) {
        this.filePath = filePath;
        this.downloadFileURL = downloadFileURL;
        this.threadCount = threadCount;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * start download
     *
     * @throws Exception
     */
    public void download() throws Exception {
        if (StringUtils.isAnyEmpty(filePath, downloadFileURL)) {
            throw new IllegalArgumentException("文件保存路径或者下载的目标文件的URL为空");
        }

        // 先获取目标文件大小
        getDownloadFileSize();
        // 然后开始下载
        doDownload(fileSize);
    }

    /**
     * get current percentage
     *
     * @return 0.15 % 53.69 %
     */
    public String getCurrentPercentage() {
        long currentSize = downloadTasks.stream()
                .mapToLong(DownloadTask::getWriteCount).sum();

        return String.format("%.2f", (currentSize * 100.0 / fileSize)) + " %";
    }

    /**
     * is done
     *
     * @return true or false
     */
    public boolean isDone() {
        long currentSize = downloadTasks.stream()
                .mapToLong(DownloadTask::getWriteCount).sum();

        return currentSize >= fileSize;
    }

    /**
     * 获取服务器文件大小
     *
     * @throws IOException
     */
    private void getDownloadFileSize() throws IOException {
        HttpURLConnection connection = getConnection();
        connection.connect();
        fileSize = connection.getContentLengthLong();
        connection.disconnect();
    }

    /**
     * 具体下载逻辑
     *
     * @param fileSize 文件大小
     * @throws Exception
     */
    private void doDownload(long fileSize) throws Exception {
        // 初始化目标下载文件
        initDownloadFile();
        // 计算每个任务下载的大小
        long taskSize = fileSize / threadCount;
        long modSize = fileSize % threadCount;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            long startPosition = taskSize * i;
            // 不然可能造成文件损坏
            long endPosition = taskSize * (i + 1) + modSize;
            DownloadTask task = new DownloadTask(startPosition, endPosition);
            downloadTasks.add(task);
            executor.submit(task);
        }
        // 关闭线程池后,不等待任务完成.(异步查询)
        executor.shutdown();
    }

    /**
     * 初始化下载保存的文件
     *
     * @throws IOException
     */
    private void initDownloadFile() throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            file.mkdir();
        }

        if (downloadFile == null) {
            String fileName = fixDownloadFilePath();
            downloadFile = new File(fileName);
            downloadFile.createNewFile();
        }
    }

    /**
     * 根据 URL 中的信息 修复文件路径
     *
     * @return 文件路径
     */
    private String fixDownloadFilePath() {
        int indexOf = StringUtils.remove(downloadFileURL, "/").lastIndexOf("/");
        indexOf = indexOf == -1 ? downloadFileURL.lastIndexOf("/") : indexOf;
        return filePath + downloadFileURL.substring(indexOf, downloadFileURL.length());
    }

    /**
     * 获取连接
     *
     * @return HttpURLConnection need to connect();
     * @throws IOException
     */
    private HttpURLConnection getConnection() throws IOException {
        URL url = new URL(downloadFileURL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(1000);
        connection.setRequestProperty(CHARSET, UTF8);
        return connection;
    }

    /**
     * 获取带有指定范围的连接
     *
     * @param startPosition 起始位置
     * @param endPosition   结束位置
     * @return HttpURLConnection need to connect();
     * @throws IOException
     */
    private HttpURLConnection getConnectionWithRang(long startPosition, long endPosition) throws IOException {
        HttpURLConnection connection = getConnection();
        connection.setRequestProperty("Range", "bytes=" + startPosition + "-" + endPosition);
        return connection;
    }

    /**
     * DownloadTask 下载的任务线程
     */
    private class DownloadTask implements Callable<Boolean> {

        private final int BUFFER = 8192;

        // 起始位置
        private long startPosition;

        // 结束位置
        private long endPosition;

        // 已经写了多少
        private long writeCount;

        public DownloadTask(long startPosition, long endPosition) {
            this.startPosition = startPosition;
            this.endPosition = endPosition;
        }

        @Override
        public Boolean call() throws IOException {
            HttpURLConnection connection = getConnectionWithRang(startPosition, endPosition);
            connection.connect();

            try (RandomAccessFile rw = new RandomAccessFile(downloadFile, "rw")) {
                // 指针指向当前线程的起始位置
                rw.seek(startPosition);

                InputStream in = connection.getInputStream();
                int hasRead;
                byte[] buffer = new byte[BUFFER];
                while ((hasRead = in.read(buffer)) > 0) {
                    rw.write(buffer, 0, hasRead);
                    writeCount += hasRead;
                }

            } finally {
                connection.disconnect();
            }

            return true;
        }

        public long getWriteCount() {
            return writeCount;
        }
    }
}
