package com.deniscerri.ytdlnis;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.deniscerri.ytdlnis.database.Video;
import com.deniscerri.ytdlnis.page.CustomCommandActivity;
import com.deniscerri.ytdlnis.service.DownloadInfo;
import com.deniscerri.ytdlnis.service.IDownloaderListener;
import com.deniscerri.ytdlnis.service.IDownloaderService;
import com.deniscerri.ytdlnis.util.NotificationUtil;
import com.yausername.youtubedl_android.DownloadProgressCallback;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class DownloaderService extends Service {

    private LocalBinder binder = new LocalBinder();
    private Map<Activity, IDownloaderListener> activities = new ConcurrentHashMap<>();
    private DownloadInfo downloadInfo = new DownloadInfo();
    private LinkedList<Video> downloadQueue = new LinkedList<>();
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private final NotificationUtil notificationUtil = App.notificationUtil;
    private Context context;
    public String downloadProcessID = "processID";
    private static final String TAG = "DownloaderService";
    private int downloadNotificationID;

    private final DownloadProgressCallback callback = (progress, etaInSeconds, line) -> {
        downloadInfo.setProgress((int) progress);
        downloadInfo.setOutputLine(line);
        downloadInfo.setDownloadQueue(downloadQueue);
        String title = getString(R.string.running_ytdlp_command);
        if (!downloadQueue.isEmpty()){
            title = downloadQueue.peek().getTitle();
        }
        notificationUtil.updateDownloadNotification(downloadNotificationID,
                line, (int) progress, downloadQueue.size(), title);

        try{
            for (Activity activity: activities.keySet()){
                activity.runOnUiThread(() -> {
                    IDownloaderListener callback = activities.get(activity);
                    callback.onDownloadProgress(downloadInfo);
                });
            }
        }catch (Exception ignored){}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Intent theIntent;
        PendingIntent pendingIntent;

        if(intent.getBooleanExtra("rebind", false)){
            return binder;
        }

        int id = intent.getIntExtra("id", 1);
        switch (id){
            case NotificationUtil.DOWNLOAD_NOTIFICATION_ID:
                theIntent = new Intent(this, MainActivity.class);
                pendingIntent = PendingIntent.getActivity(this, 0, theIntent, PendingIntent.FLAG_IMMUTABLE);
                downloadNotificationID = id;

                ArrayList<? extends Video> queue = intent.getParcelableArrayListExtra("queue");
                downloadQueue = new LinkedList<>();
                downloadQueue.addAll(queue);
                downloadInfo.setDownloadQueue(downloadQueue);

                String title = downloadInfo.getVideo().getTitle();
                Notification notification = App.notificationUtil.createDownloadServiceNotification(pendingIntent,title);
                startForeground(downloadNotificationID, notification);
                startDownload(downloadQueue);
                break;
            case NotificationUtil.COMMAND_DOWNLOAD_NOTIFICATION_ID:
                theIntent = new Intent(this, CustomCommandActivity.class);
                pendingIntent = PendingIntent.getActivity(this, 0, theIntent, PendingIntent.FLAG_IMMUTABLE);
                downloadNotificationID = id;

                String command = intent.getStringExtra("command");
                Notification command_notification = App.notificationUtil.createDownloadServiceNotification(pendingIntent,getString(R.string.running_ytdlp_command));
                startForeground(downloadNotificationID, command_notification);
                startCommandDownload(command);
                break;
        }
        return binder;
    }


    @Override
    public boolean onUnbind(Intent intent) {
        stopForeground(true);
        stopSelf();
        return super.onUnbind(intent);
    }

    public class LocalBinder extends Binder implements IDownloaderService {
        public DownloaderService getService() {
            return DownloaderService.this;
        }

        public DownloadInfo getDownloadInfo(){
            return downloadInfo;
        }

        public void addActivity(Activity activity, IDownloaderListener callback) {
            if(!activities.containsKey(activity)){
                activities.put(activity, callback);
            }
        }

        public void removeActivity(Activity activity) {
            activities.remove(activity);
        }

        public void updateQueue(ArrayList<Video> queue){
            downloadQueue.addAll(queue);
            if (downloadQueue.size() == queue.size()){
                downloadInfo.setDownloadQueue(downloadQueue);
                startDownload(downloadQueue);
            }else{
                downloadInfo.setDownloadQueue(downloadQueue);
                Toast.makeText(context, getString(R.string.added_to_queue), Toast.LENGTH_SHORT).show();
            }
        }

        public void cancelDownload(boolean cancelAll){
            try{
                YoutubeDL.getInstance().destroyProcessById(downloadProcessID);
                compositeDisposable.clear();
                //stopForeground(true);
                if (cancelAll) onDownloadCancel();
                else onDownloadEnd();
            }catch(Exception err){
                Log.e(TAG, err.getMessage());
            }
        }

        public void removeItemFromDownloadQueue(Video video){
            //if its the same video with the same download type as the current downloading one
            if (downloadInfo.getVideo().getURL().equals(video.getURL()) && downloadInfo.getVideo().getDownloadedType().equals(video.getDownloadedType())){
                cancelDownload(false);
                downloadQueue.pop();
                downloadInfo.setDownloadQueue(downloadQueue);
                startDownload(downloadQueue);
            }else {
                downloadQueue.remove(video);
            }
            Toast.makeText(context, video.getTitle() + " " + getString(R.string.removed_from_queue), Toast.LENGTH_SHORT).show();
        }
    }

    private void finishService(){
        try{
            for (Activity activity: activities.keySet()){
                activity.runOnUiThread(() -> {
                    IDownloaderListener callback = activities.get(activity);
                    callback.onDownloadServiceEnd();
                });
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void onDownloadCancel(){
        try{
            for (Activity activity: activities.keySet()){
                activity.runOnUiThread(() -> {
                    IDownloaderListener callback = activities.get(activity);
                    callback.onDownloadCancel(downloadInfo);
                });
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void onDownloadEnd(){
        try{
            for (Activity activity: activities.keySet()){
                activity.runOnUiThread(() -> {
                    IDownloaderListener callback = activities.get(activity);
                    callback.onDownloadEnd(downloadInfo);
                });
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void startDownload(LinkedList<Video> videos) {
        Video video;
        if(videos.size() == 0){
            finishService();
            return;
        }

        try {
            video = videos.peek();
        } catch (Exception e) {
            finishService();
            return;
        }

        try{
            for (Activity activity: activities.keySet()){
                activity.runOnUiThread(() -> {
                    IDownloaderListener callback = activities.get(activity);
                    callback.onDownloadStart(downloadInfo);
                });
            }
        }catch (Exception err){
            err.printStackTrace();
        }


        String url = video.getURL();
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        String type = video.getDownloadedType();
        File youtubeDLDir = getDownloadLocation(type);

        SharedPreferences sharedPreferences = context.getSharedPreferences("root_preferences", Activity.MODE_PRIVATE);

        boolean aria2 = sharedPreferences.getBoolean("aria2", false);
        if(aria2){
            request.addOption("--downloader", "libaria2c.so");
            request.addOption("--external-downloader-args", "aria2c:\"--summary-interval=1\"");
        }else{
            int concurrentFragments = sharedPreferences.getInt("concurrent_fragments", 1);
            if (concurrentFragments > 1) request.addOption("-N", concurrentFragments);
        }

        String limitRate = sharedPreferences.getString("limit_rate", "");
        if(!limitRate.equals("")) request.addOption("-r", limitRate);

        boolean writeThumbnail = sharedPreferences.getBoolean("write_thumbnail", false);
        if(writeThumbnail) {
            request.addOption("--write-thumbnail");
            request.addOption("--convert-thumbnails", "png");
        }

        request.addOption("--no-mtime");

        if (type.equals("audio")) {
            request.addOption("-x");
            String format = sharedPreferences.getString("audio_format", "");
            request.addOption("--audio-format", format);
            request.addOption("--embed-metadata");
            if(format.equals("mp3") || format.equals("m4a") || format.equals("flac")){
                boolean embedThumb = sharedPreferences.getBoolean("embed_thumbnail", false);
                if(embedThumb){
                    request.addOption("--embed-thumbnail");
                    request.addOption("--convert-thumbnails", "png");
                    try{
                        File config = new File(getCacheDir(), "config.txt");
                        String config_data = "--ppa \"ffmpeg: -c:v png -vf crop=\\\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\\\"\"";
                        FileOutputStream stream = new FileOutputStream(config);
                        stream.write(config_data.getBytes());
                        stream.close();
                        request.addOption("--config", config.getAbsolutePath());
                    }catch(Exception ignored){}
                }
            }

            boolean removeNonMusic = sharedPreferences.getBoolean("remove_non_music", false);
            if(removeNonMusic){
                request.addOption("--sponsorblock-remove", "all");
            }

        } else if (type.equals("video")) {
            boolean addChapters = sharedPreferences.getBoolean("add_chapters", false);
            if(addChapters){
                request.addOption("--sponsorblock-mark", "all");
            }
            boolean embedSubs = sharedPreferences.getBoolean("embed_subtitles", false);
            if(embedSubs){
                request.addOption("--embed-subs", "");
            }
            request.addOption("-f", "bestvideo+bestaudio/best");
            String format = sharedPreferences.getString("video_format", "");
            request.addOption("--merge-output-format", format);

            if(!format.equals("webm")){
                boolean embedThumb = sharedPreferences.getBoolean("embed_thumbnail", false);
                if(embedThumb){
                    request.addOption("--embed-thumbnail");
                }
            }
        }

        request.addOption("-o", youtubeDLDir.getAbsolutePath() + "/%(title)s.%(ext)s");

        Disposable disposable = Observable.fromCallable(() -> YoutubeDL.getInstance().execute(request, downloadProcessID, callback))
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(youtubeDLResponse -> {
                    downloadInfo.setDownloadPath(youtubeDLDir.getAbsolutePath());
                    try{
                        for (Activity activity: activities.keySet()){
                            activity.runOnUiThread(() -> {
                                IDownloaderListener callback = activities.get(activity);
                                callback.onDownloadEnd(downloadInfo);
                            });
                        }
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                    // SCAN NEXT IN QUEUE
                    videos.remove();
                    downloadInfo.setDownloadQueue(videos);
                    startDownload(videos);
                }, e -> {
                    if (BuildConfig.DEBUG) Log.e(TAG, getString(R.string.failed_download), e);
                    notificationUtil.updateDownloadNotification(NotificationUtil.DOWNLOAD_NOTIFICATION_ID,
                            getString(R.string.failed_download), 0, 0, downloadQueue.peek().getTitle());

                    try{
                        for (Activity activity: activities.keySet()){
                            activity.runOnUiThread(() -> {
                                IDownloaderListener callback = activities.get(activity);
                                callback.onDownloadError(downloadInfo);
                            });
                        }
                    }catch (Exception err){
                        err.printStackTrace();
                    }

                    // SCAN NEXT IN QUEUE
                    videos.remove();
                    downloadInfo.setDownloadQueue(videos);
                    startDownload(videos);
                });
        compositeDisposable.add(disposable);
    }

    private void startCommandDownload(String text){
        if(!text.startsWith("yt-dlp ")){
            Toast.makeText(context, "Wrong input! Try Again!", Toast.LENGTH_SHORT).show();
            finishService();
            return;
        }
        text = text.substring(6).trim();

        YoutubeDLRequest request = new YoutubeDLRequest(Collections.emptyList());
        String commandRegex = "\"([^\"]*)\"|(\\S+)";
        Matcher m = Pattern.compile(commandRegex).matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                request.addOption(m.group(1));
            } else {
                request.addOption(m.group(2));
            }
        }

        SharedPreferences sharedPreferences = context.getSharedPreferences("root_preferences", Activity.MODE_PRIVATE);
        String downloadsDir = sharedPreferences.getString("command_path", getString(R.string.command_path));
        File youtubeDLDir = new File(downloadsDir);
        if (!youtubeDLDir.exists()) {
            boolean isDirCreated = youtubeDLDir.mkdir();
            if (!isDirCreated) {
                Toast.makeText(context, R.string.failed_making_directory, Toast.LENGTH_LONG).show();
            }
        }
        request.addOption("-o", youtubeDLDir.getAbsolutePath() + "/%(title)s.%(ext)s");

        Disposable disposable = Observable.fromCallable(() -> YoutubeDL.getInstance().execute(request, downloadProcessID, callback))
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(youtubeDLResponse -> {
                    downloadInfo.setOutputLine(youtubeDLResponse.getOut());
                    try{
                        for (Activity activity: activities.keySet()){
                            activity.runOnUiThread(() -> {
                                IDownloaderListener callback = activities.get(activity);
                                callback.onDownloadEnd(downloadInfo);
                                callback.onDownloadServiceEnd();
                            });
                        }
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }, e -> {
                    downloadInfo.setOutputLine(e.getMessage());
                    try{
                        for (Activity activity: activities.keySet()){
                            activity.runOnUiThread(() -> {
                                IDownloaderListener callback = activities.get(activity);
                                callback.onDownloadError(downloadInfo);
                                callback.onDownloadServiceEnd();
                            });
                        }
                    }catch (Exception err){
                        err.printStackTrace();
                    }
                });
        compositeDisposable.add(disposable);
    }


    @NonNull
    private File getDownloadLocation(String type) {
        SharedPreferences sharedPreferences = context.getSharedPreferences("root_preferences", Activity.MODE_PRIVATE);
        String downloadsDir;
        if (type.equals("audio")) {
            downloadsDir = sharedPreferences.getString("music_path", getString(R.string.music_path));
        } else {
            downloadsDir = sharedPreferences.getString("video_path", getString(R.string.video_path));
        }

        File youtubeDLDir = new File(downloadsDir);
        if (!youtubeDLDir.exists()) {
            boolean isDirCreated = youtubeDLDir.mkdir();
            if (!isDirCreated) {
                notificationUtil.updateDownloadNotification(NotificationUtil.DOWNLOAD_NOTIFICATION_ID,
                        getString(R.string.failed_making_directory), 0, 0, downloadQueue.peek().getTitle());
            }
        }
        return youtubeDLDir;
    }
}