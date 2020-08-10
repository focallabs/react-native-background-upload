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
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import okhttp3.FormBody;
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

  class ChunkFileTask extends AsyncTask<Integer, Void, String> {

    private String currentFile, newPath;
    private long position;
    private IOException error;
    private long length;

    public ChunkFileTask(String currentFile, String newPath, long position, long length) {
      this.currentFile = currentFile;
      this.newPath = newPath;
      this.position = position;
      this.length = length;
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
        // Log.d("ChunkFileTask", "Start split file => : " + this.currentFile + "  with index : " + this.position);

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

        //Log.d("ChunkFileTask", "Current file size :" + fileSize);
        //Log.d("ChunkFileTask", "Remain file size should be :" + (fileSize - position - 1));

        fis = new FileInputStream(currentFile);
        int size = 1 * 1024 * 1014; //1 mb

        if (position + length > fileSize) {
          length = fileSize - position;
        }
        int totalBytesRead = 0;

        byte buffer[] = new byte[size];
        fis.skip(position);

        fos = new FileOutputStream(newPath);

        //Log.d("ChunkFileTask", "position=" + position + " length=" + length);

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

        //Log.d("ChunkFileTask", "totalBytesRead :" + totalBytesRead);
        //Log.d("ChunkFileTask", "Remain file size :" + newFile.length());
        //Log.d("ChunkFileTask", "Split file success => " + newPath);

      } catch (FileNotFoundException e) {
        //Log.d("ChunkFileTask", "Split file error " + e.getLocalizedMessage());

        this.error = e;
        return null;
      } catch (IOException e) {

        //Log.d("ChunkFileTask", "Split file error " + e.getLocalizedMessage());
        this.error = e;
        return null;

      } finally {
        if (fis != null) {
          try {
            fis.close();
          } catch (IOException e) {
            //Log.d("ChunkFileTask", "Split file error " + e.getLocalizedMessage());
            this.error = e;
          }
        }
        if (fos != null) {
          try {
            fos.close();
          } catch (IOException e) {
            //Log.d("ChunkFileTask", "Split file error " + e.getLocalizedMessage());
            this.error = e;
          }
        }
      }

      return hash(this.newPath, "md5");
    }

    @Override
    protected void onPostExecute(String result) {

    }
  }
  class GetPresignUrlTask extends AsyncTask<String, Void, String> {
    private String mUrl;
    private String mFileName;
    private String mS3UploadId;
    private String mAccessToken;
    private int partNumber;
    private OkHttpClient mClient;

    public GetPresignUrlTask(String url, String fileName, String s3UploadId, int partNumber, String accessToken) {
      this.mUrl = url;
      this.mFileName = fileName;
      this.mS3UploadId = s3UploadId;
      this.mAccessToken = accessToken;
      this.partNumber = partNumber;
    }

    @Override
    protected void onPreExecute() {
      super.onPreExecute();
      this.mClient = new OkHttpClient();
    }

    @Override
    protected String doInBackground(String... strings) {

      RequestBody requestBody = new FormBody.Builder()
          .add("videoName", this.mFileName)
          .add("uploadId", this.mS3UploadId)
          .add("partNumber", String.valueOf(this.partNumber))
          .build();
      //Log.d(TAG, "url: " + this.mUrl);
      Request request = new Request.Builder()
          .url(this.mUrl)
          .addHeader("Content-Type", "application/x-www-form-urlencoded")
          .addHeader("Authorization", "Bearer " + this.mAccessToken)
          .post(requestBody)
          .build();
      try (Response response = mClient.newCall(request).execute()) {
        String jsonData = response.body().string();
        //Log.d(TAG, jsonData);
        JSONObject jsonObject = new JSONObject(jsonData);
        return jsonObject.getString("presignedUrl");
      } catch (IOException e) {
        e.printStackTrace();
      } catch (JSONException e) {
        e.printStackTrace();
      }
      return null;
    }

    @Override
    protected void onPostExecute(String s) {
      super.onPostExecute(s);
    }
  }

  public String hash(String filepath, String algorithm) {
    try {
      Map<String, String> algorithms = new HashMap<>();

      algorithms.put("md5", "MD5");
      algorithms.put("sha1", "SHA-1");
      algorithms.put("sha224", "SHA-224");
      algorithms.put("sha256", "SHA-256");
      algorithms.put("sha384", "SHA-384");
      algorithms.put("sha512", "SHA-512");

      if (!algorithms.containsKey(algorithm)) throw new Exception("Invalid hash algorithm");

      File file = new File(filepath);

      if (file.isDirectory()) {
        return null;
      }

      if (!file.exists()) {
        return null;
      }

      MessageDigest md = MessageDigest.getInstance(algorithms.get(algorithm));

      FileInputStream inputStream = new FileInputStream(filepath);
      byte[] buffer = new byte[1024 * 10]; // 10 KB Buffer

      int read;
      while ((read = inputStream.read(buffer)) != -1) {
        md.update(buffer, 0, read);
      }

      StringBuilder hexString = new StringBuilder();
      for (byte digestByte : md.digest())
        hexString.append(String.format("%02x", digestByte));

      return hexString.toString();
    } catch (Exception ex) {
      ex.printStackTrace();
      return null;
    }
  }

  private String getPresignUrl(String url, String fileName, String s3UploadId, int partNumber, String accessToken) throws IOException, JSONException {
    OkHttpClient client = new OkHttpClient();

    RequestBody requestBody = new FormBody.Builder()
        .add("videoName", fileName)
        .add("uploadId", s3UploadId)
        .add("partNumber", String.valueOf(partNumber))
        .build();
    //Log.d(TAG, "url: " + url);
    Request request = new Request.Builder()
        .url(url)
        .addHeader("Content-Type", "application/x-www-form-urlencoded")
        .addHeader("Authorization", "Bearer " + accessToken)
        .post(requestBody)
        .build();
    try (Response response = client.newCall(request).execute()) {
      String jsonData = response.body().string();
      //Log.d(TAG, jsonData);
      JSONObject jsonObject = new JSONObject(jsonData);
      return jsonObject.getString("presignedUrl");
    }
  }

  /*
   * Starts a file upload.
   * Returns a promise with the string ID of the upload.
   */
  @ReactMethod
  public void startMultiPartUpload(ReadableMap options, final Promise promise) {
    //Log.d(TAG, "startMultiPartUpload");
    for (String key : new String[]{"path", "fileName", "getPresignUrl", "accessToken", "s3UploadId"}) {
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

    String filePath = options.getString("path");
    String method = options.hasKey("method") && options.getType("method") == ReadableType.String ? options.getString("method") : "POST";

    final String customUploadId = options.hasKey("customUploadId") && options.getType("method") == ReadableType.String ? options.getString("customUploadId") : null;

    try {

      int beginPart = options.getInt("beginPart");
      int totalPart = options.getInt("totalPart");
      int partSize = options.getInt("partSize");
      String accessToken = options.getString("accessToken");
      File fileInfo = new File(filePath);
      //Log.d(TAG, "file exist: " + fileInfo.exists());
      //Log.d(TAG, "filePath: " + filePath);
      final long totalSize = fileInfo.length();

      //Log.d(TAG, "beginPart: " + beginPart);

      //Log.d(TAG, "totalSize: " + totalSize);

      String getPresignUrl = options.getString("getPresignUrl");
      String fileName = options.getString("fileName");
      String s3UploadId = options.getString("s3UploadId");

      uploartPart(options, beginPart);

      promise.resolve(customUploadId);
    } catch (Exception exc) {
      Log.e(TAG, exc.getMessage(), exc);
      promise.reject(exc);
    }
  }

  private void uploartPart(final ReadableMap options, final int currentPart) throws IOException, JSONException {


    WritableMap notification = new WritableNativeMap();
    notification.putBoolean("enabled", true);

    if (options.hasKey("notification")) {
      notification.merge(options.getMap("notification"));
    }

    String requestType = "raw";

    if (options.hasKey("type")) {
      requestType = options.getString("type");
    }


    String filePath = options.getString("path");
    String method = options.hasKey("method") && options.getType("method") == ReadableType.String ? options.getString("method") : "POST";

    final String customUploadId = options.hasKey("customUploadId") && options.getType("method") == ReadableType.String ? options.getString("customUploadId") : null;

    final int totalPart = options.getInt("totalPart");
    int partSize = options.getInt("partSize");
    String accessToken = options.getString("accessToken");
    File fileInfo = new File(filePath);
    //Log.d(TAG, "file exist: " + fileInfo.exists());
    //Log.d(TAG, "filePath: " + filePath);
    final long totalSize = fileInfo.length();

    String getPresignUrl = options.getString("getPresignUrl");
    String fileName = options.getString("fileName");
    String s3UploadId = options.getString("s3UploadId");

    String presignUrl = null;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
//      presignUrl = getPresignUrl(getPresignUrl, fileName, s3UploadId, currentPart, accessToken);
      try {
        presignUrl = new GetPresignUrlTask(getPresignUrl, fileName, s3UploadId, currentPart, accessToken).execute().get();
      } catch (ExecutionException e) {
        e.printStackTrace();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      //Log.d(TAG, "presignUrl: " + presignUrl);
    }

    String tempFilePath = getReactApplicationContext().getCacheDir().getAbsolutePath() + "/" + fileName + "_" + currentPart;

    //Log.d(TAG, "tempFilePath: " + tempFilePath);
    //Log.d(TAG, "totalSize: " + totalSize);
    long length;
    if (totalPart == currentPart) {
      length = totalSize - partSize * (currentPart - 1);
      //Log.d(TAG, "length: " + length);
    } else {
      length = partSize;
    }
    final long position = (currentPart - 1) * partSize;

    String fileMD5 = null;
    try {
      fileMD5 = new ChunkFileTask(filePath, tempFilePath, position, length).execute().get();
    } catch (ExecutionException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    final String finalFileMD = fileMD5;
    statusDelegate = new UploadStatusDelegate() {
      @Override
      public void onProgress(Context context, UploadInfo uploadInfo) {
        // ((position + (data.progress * length) / 100) * 100) / totalSize,
        WritableMap params = Arguments.createMap();
        params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
        //Log.d(TAG, "position=" + position + ", uploadedBytes=" + uploadInfo.getUploadedBytes() + ", totalSize=" + totalSize);
        double percent = (position + uploadInfo.getUploadedBytes()) * 100 / totalSize;
        //Log.d(TAG, "percent=" + percent + ", plus=" + (position + uploadInfo.getUploadedBytes()));
        params.putInt("progress", (int) percent);
        params.putInt("currentPart", currentPart);
        params.putString("md5", finalFileMD);

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
        if (currentPart == totalPart) {
          WritableMap params = Arguments.createMap();
          params.putString("id", customUploadId != null ? customUploadId : uploadInfo.getUploadId());
          params.putInt("responseCode", serverResponse.getHttpCode());
          params.putString("responseBody", serverResponse.getBodyAsString());
          sendEvent("completed", params);
        } else {
          try {
            uploartPart(options, currentPart + 1);
          } catch (IOException e) {
            e.printStackTrace();
          } catch (JSONException e) {
            e.printStackTrace();
          }
        }
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
      request = new BinaryUploadRequest(this.getReactApplicationContext(), customUploadId, presignUrl)
          .setFileToUpload(tempFilePath);
    } else {
      if (!options.hasKey("field")) {
        throw new IllegalArgumentException("field is required field for multipart type.");
      }

      if (options.getType("field") != ReadableType.String) {
        throw new IllegalArgumentException("field must be string.");
      }

      request = new MultipartUploadRequest(this.getReactApplicationContext(), customUploadId, presignUrl)
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
        throw new IllegalArgumentException("Parameters supported only in multipart type");
      }

      ReadableMap parameters = options.getMap("parameters");
      ReadableMapKeySetIterator keys = parameters.keySetIterator();

      while (keys.hasNextKey()) {
        String key = keys.nextKey();

        if (parameters.getType(key) != ReadableType.String) {
          throw new IllegalArgumentException("Parameters must be string key/values. Value was invalid for '" + key + "'");
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
          throw new IllegalArgumentException("Headers must be string key/values.  Value was invalid for '" + key + "'");
        }
        request.addHeader(key, headers.getString(key));
      }
    }

    String uploadId = request.startUpload();
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
