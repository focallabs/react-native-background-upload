package com.vydia.RNUploader;

import android.content.Context;
import android.support.annotation.Nullable;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import net.gotev.uploadservice.BinaryUploadRequest;
import net.gotev.uploadservice.HttpUploadRequest;
import net.gotev.uploadservice.MultipartUploadRequest;
import net.gotev.uploadservice.ServerResponse;
import net.gotev.uploadservice.UploadInfo;
import net.gotev.uploadservice.UploadNotificationConfig;
import net.gotev.uploadservice.UploadService;
import net.gotev.uploadservice.UploadStatusDelegate;
import net.gotev.uploadservice.okhttp.OkHttpStack;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Created by stephen on 12/8/16.
 */
public class UploaderModule extends ReactContextBaseJavaModule {
  private static final String TAG = "UploaderBridge";

  private UploadStatusDelegate statusDelegate;

  public UploaderModule(ReactApplicationContext reactContext) {
    super(reactContext);
    UploadService.NAMESPACE = reactContext.getApplicationInfo().packageName;
    UploadService.HTTP_STACK = new OkHttpStack();
  }

  @Override
  public String getName() {
    return "RNFileUploader";
  }

  /*
  Sends an event to the JS module.
   */
  private void sendEvent(String eventName, @Nullable WritableMap params) {
    this.getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("RNFileUploader-" + eventName, params);
  }

  /*
  Gets file information for the path specified.  Example valid path is: /storage/extSdCard/DCIM/Camera/20161116_074726.mp4
  Returns an object such as: {extension: "mp4", size: "3804316", exists: true, mimeType: "video/mp4", name: "20161116_074726.mp4"}
   */
  @ReactMethod
  public void getFileInfo(String path, final Promise promise) {
    try {
      WritableMap params = Arguments.createMap();
      File fileInfo = new File(path);
      params.putString("name", fileInfo.getName());
      if (!fileInfo.exists() || !fileInfo.isFile())
      {
        params.putBoolean("exists", false);
      }
      else
      {
        params.putBoolean("exists", true);
        params.putString("size",Long.toString(fileInfo.length())); //use string form of long because there is no putLong and converting to int results in a max size of 17.2 gb, which could happen.  Javascript will need to convert it to a number
        String extension = MimeTypeMap.getFileExtensionFromUrl(path);
        params.putString("extension",extension);
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        params.putString("mimeType", mimeType);
      }

      promise.resolve(params);
    } catch (Exception exc) {
      Log.e(TAG, exc.getMessage(), exc);
      promise.reject(exc);
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.CUPCAKE)
  public static class ChunkFileTask extends AsyncTask<Integer, Void, String> {

    private String currentFile, newPath;
    private long position;
    private Promise promise;
    private IOException error;
    private long length;

    public ChunkFileTask(String currentFile, String newPath, long position, long length, Promise promise) {
      this.currentFile = currentFile;
      this.newPath = newPath;
      this.position = position;
      this.length = length;
      this.promise = promise;
      if (this.currentFile.startsWith("file://")) {
        this.currentFile = this.currentFile.replace("file://", "");
      }
      if (this.newPath.startsWith("file://")) {
        this.newPath = this.newPath.replace("file://", "");
      }
    }

    @Override
    protected String doInBackground(Integer... params) {
      FileInputStream fis = null;
      FileOutputStream fos = null;
      try {
        Log.d("FileUtil", "Start split file => : " + this.currentFile + "  with index : " + this.position);

        File file = new File(currentFile);
        if (!file.exists()) {
          this.error = new FileNotFoundException("File not existed");
          return "ERROR";
        }
        long fileSize = file.length();
        File newFile = new File(newPath);
        if (newFile.exists()) {
          newFile.delete();
        }
        newFile.createNewFile();

        Log.d("FileUtil", "Current file size :" + fileSize);
        Log.d("FileUtil", "Remain file size should be :" + (fileSize - position - 1));

        fis = new FileInputStream(currentFile);
        int size = 1 * 1024 * 1014; //1 mb

        if (position + length > fileSize) {
          length = fileSize - position;
        }
        int totalBytesRead = 0;

        byte buffer[] = new byte[size];
        fis.skip(position);

        fos = new FileOutputStream(newPath);

        Log.d("FileUtil", "position=" + position + " length=" + length);

        while (totalBytesRead < length) {
          if (size + totalBytesRead > length && totalBytesRead < length) {
            size = (int) (length - totalBytesRead);
          }
          int bytesRead = fis.read(buffer, 0, size);
          if (bytesRead == -1 || totalBytesRead >= length) {
            fos.close();
            break;
          }
          totalBytesRead += bytesRead;
          fos.write(buffer, 0, bytesRead);
          fos.flush();
        }

        Log.d("FileUtil", "totalBytesRead :" + totalBytesRead);
        Log.d("FileUtil", "Remain file size :" + newFile.length());
        Log.d("FileUtil", "Split file success => " + newPath);

      } catch (FileNotFoundException e) {
        Log.d("FileUtil", "Split file error " + e.getLocalizedMessage());

        this.error = e;
        return "ERROR";
      } catch (IOException e) {

        Log.d("FileUtil", "Split file error " + e.getLocalizedMessage());
        this.error = e;
        return "ERROR";

      } finally {
        if (fis != null) {
          try {
            fis.close();
          } catch (IOException e) {
            Log.d("FileUtil", "Split file error " + e.getLocalizedMessage());
            this.error = e;
          }
        }
        if (fos != null) {
          try {
            fos.close();
          } catch (IOException e) {
            Log.d("FileUtil", "Split file error " + e.getLocalizedMessage());
            this.error = e;
          }
        }
      }
      return "OK";
    }

    @Override
    protected void onPostExecute(String result) {
      if (result.equalsIgnoreCase("ERROR")) {
        promise.reject(this.error);
      } else {
        promise.resolve(newPath);
      }
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.KITKAT)
  private String getPresignUrl(String url, String fileName, String s3UploadId, int partNumber) throws IOException, JSONException {
    MediaType JSON
        = MediaType.get("application/json; charset=utf-8");

    OkHttpClient client = new OkHttpClient();

    JSONObject json = new JSONObject();
    json.put("videoName", fileName);
    json.put("uploadId", s3UploadId);
    json.put("partNumber", partNumber);

    RequestBody body = RequestBody.create(JSON, json.toString());
    Request request = new Request.Builder()
        .url(url)
        .post(body)
        .build();
    try (Response response = client.newCall(request).execute()) {
      String jsonData = response.body().toString();
      JSONObject jsonObject = new JSONObject(jsonData);
      return jsonObject.getString("presignedUrl");
    }
  }

  /*
   * Starts a file upload.
   * Returns a promise with the string ID of the upload.
   */
  @RequiresApi(api = Build.VERSION_CODES.KITKAT)
  @ReactMethod
  public void startMultiPartUpload(ReadableMap options, final Promise promise) {
    for (String key : new String[]{"url", "path", "fileName", "getPresignUrl"}) {
      if (!options.hasKey(key)) {
        promise.reject(new IllegalArgumentException("Missing '" + key + "' field."));
        return;
      }
      if (options.getType(key) != ReadableType.String) {
        promise.reject(new IllegalArgumentException(key + " must be a string."));
        return;
      }
    }
    for (String key : new String[]{"beginPart", "totalPart", "partSize"}) {
      if (!options.hasKey(key)) {
        promise.reject(new IllegalArgumentException("Missing '" + key + "' field."));
        return;
      }
      if (options.getType(key) != ReadableType.Number) {
        promise.reject(new IllegalArgumentException(key + " must be a number."));
        return;
      }
    }

    if (options.hasKey("headers") && options.getType("headers") != ReadableType.Map) {
      promise.reject(new IllegalArgumentException("headers must be a hash."));
      return;
    }

    if (options.hasKey("notification") && options.getType("notification") != ReadableType.Map) {
      promise.reject(new IllegalArgumentException("notification must be a hash."));
      return;
    }

    String requestType = "raw";

    if (options.hasKey("type")) {
      requestType = options.getString("type");
      if (requestType == null) {
        promise.reject(new IllegalArgumentException("type must be string."));
        return;
      }

      if (!requestType.equals("raw") && !requestType.equals("multipart")) {
        promise.reject(new IllegalArgumentException("type should be string: raw or multipart."));
        return;
      }
    }

    WritableMap notification = new WritableNativeMap();
    notification.putBoolean("enabled", true);

    if (options.hasKey("notification")) {
      notification.merge(options.getMap("notification"));
    }

    String url = options.getString("url");
    String filePath = options.getString("path");
    String method = options.hasKey("method") && options.getType("method") == ReadableType.String ? options.getString("method") : "POST";

    final String customUploadId = options.hasKey("customUploadId") && options.getType("method") == ReadableType.String ? options.getString("customUploadId") : null;

    try {

      int beginPart = options.getInt("beginPart");
      int totalPart = options.getInt("totalPart");
      int partSize = options.getInt("partSize");
      File fileInfo = new File(filePath);
      final long totalSize = fileInfo.length();

      for (int currentPart = beginPart; currentPart <= totalPart; currentPart++) {
        String getPresignUrl = options.getString("getPresignUrl");
        String fileName = options.getString("fileName");
        String s3UploadId = options.getString("s3UploadId");
        String presignUrl = getPresignUrl(getPresignUrl, fileName, s3UploadId, currentPart);

        String tempFilePath = getReactApplicationContext().getCacheDir().getAbsolutePath() + "/" + fileName + "_" + currentPart;

        long length;
        if (totalPart == currentPart) {
          length = totalSize - partSize * (currentPart - 1);
        } else {
          length = partSize;
        }
        final long position = (currentPart - 1) * partSize;

        new ChunkFileTask(filePath, tempFilePath, position, length, promise).execute();

        statusDelegate = new UploadStatusDelegate() {
          @Override
          public void onProgress(Context context, UploadInfo uploadInfo) {
            WritableMap params = Arguments.createMap();
            params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
            int percent = (int) ((position + uploadInfo.getUploadedBytes())* 100 / totalSize);
            params.putInt("progress", uploadInfo.getProgressPercent()); //0-100
            sendEvent("progress", params);
          }

          @Override
          public void onError(Context context, UploadInfo uploadInfo, ServerResponse serverResponse, Exception exception) {
            WritableMap params = Arguments.createMap();
            params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
            if (serverResponse != null) {
              params.putInt("responseCode", serverResponse.getHttpCode());
              params.putString("responseBody", serverResponse.getBodyAsString());
            }

            // Make sure we do not try to call getMessage() on a null object
            if (exception != null){
              params.putString("error", exception.getMessage());
            } else {
              params.putString("error", "Unknown exception");
            }

            sendEvent("error", params);
          }

          @Override
          public void onCompleted(Context context, UploadInfo uploadInfo, ServerResponse serverResponse) {
            WritableMap params = Arguments.createMap();
            params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
            params.putInt("responseCode", serverResponse.getHttpCode());
            params.putString("responseBody", serverResponse.getBodyAsString());
            sendEvent("completed", params);
          }

          @Override
          public void onCancelled(Context context, UploadInfo uploadInfo) {
            WritableMap params = Arguments.createMap();
            params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
            sendEvent("cancelled", params);
          }
        };

        HttpUploadRequest<?> request;

        if (requestType.equals("raw")) {
          request = new BinaryUploadRequest(this.getReactApplicationContext(), customUploadId, url)
              .setFileToUpload(tempFilePath);
        } else {
          if (!options.hasKey("field")) {
            promise.reject(new IllegalArgumentException("field is required field for multipart type."));
            return;
          }

          if (options.getType("field") != ReadableType.String) {
            promise.reject(new IllegalArgumentException("field must be string."));
            return;
          }

          request = new MultipartUploadRequest(this.getReactApplicationContext(), customUploadId, url)
              .addFileToUpload(tempFilePath, options.getString("field"));
        }


        request.setMethod(method)
            .setMaxRetries(2)
            .setDelegate(statusDelegate);

        if (notification.getBoolean("enabled")) {

          UploadNotificationConfig notificationConfig = new UploadNotificationConfig();

          if (notification.hasKey("notificationChannel")){
            notificationConfig.setNotificationChannelId(notification.getString("notificationChannel"));
          }

          if (notification.hasKey("autoClear") && notification.getBoolean("autoClear")){
            notificationConfig.getCompleted().autoClear = true;
          }

          if (notification.hasKey("enableRingTone") && notification.getBoolean("enableRingTone")){
            notificationConfig.setRingToneEnabled(true);
          }

          if (notification.hasKey("onCompleteTitle")) {
            notificationConfig.getCompleted().title = notification.getString("onCompleteTitle");
          }

          if (notification.hasKey("onCompleteMessage")) {
            notificationConfig.getCompleted().message = notification.getString("onCompleteMessage");
          }

          if (notification.hasKey("onErrorTitle")) {
            notificationConfig.getError().title = notification.getString("onErrorTitle");
          }

          if (notification.hasKey("onErrorMessage")) {
            notificationConfig.getError().message = notification.getString("onErrorMessage");
          }

          if (notification.hasKey("onProgressTitle")) {
            notificationConfig.getProgress().title = notification.getString("onProgressTitle");
          }

          if (notification.hasKey("onProgressMessage")) {
            notificationConfig.getProgress().message = notification.getString("onProgressMessage");
          }

          if (notification.hasKey("onCancelledTitle")) {
            notificationConfig.getCancelled().title = notification.getString("onCancelledTitle");
          }

          if (notification.hasKey("onCancelledMessage")) {
            notificationConfig.getCancelled().message = notification.getString("onCancelledMessage");
          }

          request.setNotificationConfig(notificationConfig);

        }

        if (options.hasKey("parameters")) {
          if (requestType.equals("raw")) {
            promise.reject(new IllegalArgumentException("Parameters supported only in multipart type"));
            return;
          }

          ReadableMap parameters = options.getMap("parameters");
          ReadableMapKeySetIterator keys = parameters.keySetIterator();

          while (keys.hasNextKey()) {
            String key = keys.nextKey();

            if (parameters.getType(key) != ReadableType.String) {
              promise.reject(new IllegalArgumentException("Parameters must be string key/values. Value was invalid for '" + key + "'"));
              return;
            }

            request.addParameter(key, parameters.getString(key));
          }
        }

        if (options.hasKey("headers")) {
          ReadableMap headers = options.getMap("headers");
          ReadableMapKeySetIterator keys = headers.keySetIterator();
          while (keys.hasNextKey()) {
            String key = keys.nextKey();
            if (headers.getType(key) != ReadableType.String) {
              promise.reject(new IllegalArgumentException("Headers must be string key/values.  Value was invalid for '" + key + "'"));
              return;
            }
            request.addHeader(key, headers.getString(key));
          }
        }

        String uploadId = request.startUpload();
      }
      promise.resolve(customUploadId);
    } catch (Exception exc) {
      Log.e(TAG, exc.getMessage(), exc);
      promise.reject(exc);
    }
  }


  /*
   * Starts a file upload.
   * Returns a promise with the string ID of the upload.
   */
  @ReactMethod
  public void startUpload(ReadableMap options, final Promise promise) {
    for (String key : new String[]{"url", "path"}) {
      if (!options.hasKey(key)) {
        promise.reject(new IllegalArgumentException("Missing '" + key + "' field."));
        return;
      }
      if (options.getType(key) != ReadableType.String) {
        promise.reject(new IllegalArgumentException(key + " must be a string."));
        return;
      }
    }

    if (options.hasKey("headers") && options.getType("headers") != ReadableType.Map) {
      promise.reject(new IllegalArgumentException("headers must be a hash."));
      return;
    }

    if (options.hasKey("notification") && options.getType("notification") != ReadableType.Map) {
      promise.reject(new IllegalArgumentException("notification must be a hash."));
      return;
    }

    String requestType = "raw";

    if (options.hasKey("type")) {
      requestType = options.getString("type");
      if (requestType == null) {
        promise.reject(new IllegalArgumentException("type must be string."));
        return;
      }

      if (!requestType.equals("raw") && !requestType.equals("multipart")) {
        promise.reject(new IllegalArgumentException("type should be string: raw or multipart."));
        return;
      }
    }

    WritableMap notification = new WritableNativeMap();
    notification.putBoolean("enabled", true);

    if (options.hasKey("notification")) {
      notification.merge(options.getMap("notification"));
    }

    String url = options.getString("url");
    String filePath = options.getString("path");
    String method = options.hasKey("method") && options.getType("method") == ReadableType.String ? options.getString("method") : "POST";

    final String customUploadId = options.hasKey("customUploadId") && options.getType("method") == ReadableType.String ? options.getString("customUploadId") : null;

    try {
      statusDelegate = new UploadStatusDelegate() {
        @Override
        public void onProgress(Context context, UploadInfo uploadInfo) {
          WritableMap params = Arguments.createMap();
          params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
          params.putInt("progress", uploadInfo.getProgressPercent()); //0-100
          sendEvent("progress", params);
        }

        @Override
        public void onError(Context context, UploadInfo uploadInfo, ServerResponse serverResponse, Exception exception) {
          WritableMap params = Arguments.createMap();
          params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
          if (serverResponse != null) {
            params.putInt("responseCode", serverResponse.getHttpCode());
            params.putString("responseBody", serverResponse.getBodyAsString());
          }

          // Make sure we do not try to call getMessage() on a null object
          if (exception != null){
            params.putString("error", exception.getMessage());
          } else {
            params.putString("error", "Unknown exception");
          }

          sendEvent("error", params);
        }

        @Override
        public void onCompleted(Context context, UploadInfo uploadInfo, ServerResponse serverResponse) {
          WritableMap params = Arguments.createMap();
          params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
          params.putInt("responseCode", serverResponse.getHttpCode());
          params.putString("responseBody", serverResponse.getBodyAsString());
          sendEvent("completed", params);
        }

        @Override
        public void onCancelled(Context context, UploadInfo uploadInfo) {
          WritableMap params = Arguments.createMap();
          params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
          sendEvent("cancelled", params);
        }
      };

      HttpUploadRequest<?> request;

      if (requestType.equals("raw")) {
        request = new BinaryUploadRequest(this.getReactApplicationContext(), customUploadId, url)
                .setFileToUpload(filePath);
      } else {
        if (!options.hasKey("field")) {
          promise.reject(new IllegalArgumentException("field is required field for multipart type."));
          return;
        }

        if (options.getType("field") != ReadableType.String) {
          promise.reject(new IllegalArgumentException("field must be string."));
          return;
        }

        request = new MultipartUploadRequest(this.getReactApplicationContext(), customUploadId, url)
                .addFileToUpload(filePath, options.getString("field"));
      }


      request.setMethod(method)
        .setMaxRetries(2)
        .setDelegate(statusDelegate);

      if (notification.getBoolean("enabled")) {

        UploadNotificationConfig notificationConfig = new UploadNotificationConfig();

        if (notification.hasKey("notificationChannel")){
          notificationConfig.setNotificationChannelId(notification.getString("notificationChannel"));
        }

        if (notification.hasKey("autoClear") && notification.getBoolean("autoClear")){
          notificationConfig.getCompleted().autoClear = true;
        }

        if (notification.hasKey("enableRingTone") && notification.getBoolean("enableRingTone")){
          notificationConfig.setRingToneEnabled(true);
        }

        if (notification.hasKey("onCompleteTitle")) {
          notificationConfig.getCompleted().title = notification.getString("onCompleteTitle");
        }

        if (notification.hasKey("onCompleteMessage")) {
          notificationConfig.getCompleted().message = notification.getString("onCompleteMessage");
        }

        if (notification.hasKey("onErrorTitle")) {
          notificationConfig.getError().title = notification.getString("onErrorTitle");
        }

        if (notification.hasKey("onErrorMessage")) {
          notificationConfig.getError().message = notification.getString("onErrorMessage");
        }

        if (notification.hasKey("onProgressTitle")) {
          notificationConfig.getProgress().title = notification.getString("onProgressTitle");
        }

        if (notification.hasKey("onProgressMessage")) {
          notificationConfig.getProgress().message = notification.getString("onProgressMessage");
        }

        if (notification.hasKey("onCancelledTitle")) {
          notificationConfig.getCancelled().title = notification.getString("onCancelledTitle");
        }

        if (notification.hasKey("onCancelledMessage")) {
          notificationConfig.getCancelled().message = notification.getString("onCancelledMessage");
        }

        request.setNotificationConfig(notificationConfig);

      }

      if (options.hasKey("parameters")) {
        if (requestType.equals("raw")) {
          promise.reject(new IllegalArgumentException("Parameters supported only in multipart type"));
          return;
        }

        ReadableMap parameters = options.getMap("parameters");
        ReadableMapKeySetIterator keys = parameters.keySetIterator();

        while (keys.hasNextKey()) {
          String key = keys.nextKey();

          if (parameters.getType(key) != ReadableType.String) {
            promise.reject(new IllegalArgumentException("Parameters must be string key/values. Value was invalid for '" + key + "'"));
            return;
          }

          request.addParameter(key, parameters.getString(key));
        }
      }

      if (options.hasKey("headers")) {
        ReadableMap headers = options.getMap("headers");
        ReadableMapKeySetIterator keys = headers.keySetIterator();
        while (keys.hasNextKey()) {
          String key = keys.nextKey();
          if (headers.getType(key) != ReadableType.String) {
            promise.reject(new IllegalArgumentException("Headers must be string key/values.  Value was invalid for '" + key + "'"));
            return;
          }
          request.addHeader(key, headers.getString(key));
        }
      }

      String uploadId = request.startUpload();
      promise.resolve(uploadId);
    } catch (Exception exc) {
      Log.e(TAG, exc.getMessage(), exc);
      promise.reject(exc);
    }
  }

  /*
   * Cancels file upload
   * Accepts upload ID as a first argument, this upload will be cancelled
   * Event "cancelled" will be fired when upload is cancelled.
   */
  @ReactMethod
  public void cancelUpload(String cancelUploadId, final Promise promise) {
    if (!(cancelUploadId instanceof String)) {
      promise.reject(new IllegalArgumentException("Upload ID must be a string"));
      return;
    }
    try {
      UploadService.stopUpload(cancelUploadId);
      promise.resolve(true);
    } catch (Exception exc) {
      Log.e(TAG, exc.getMessage(), exc);
      promise.reject(exc);
    }
  }

}
