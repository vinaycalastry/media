/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.exoplayer.drm;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.StatsDataSource;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** A {@link MediaDrmCallback} that makes requests using {@link HttpDataSource} instances. */
@UnstableApi public final class DrmTodayWidevineCallback implements MediaDrmCallback {
  /**
   * Logger tag for this class
   */
  private static final String TAG = "DrmCallback";

  /**
   * The assetId of the requested asset.
   */
  private static final String DRMTODAY_ASSET_ID_PARAM = "assetId";
  /**
   * (optional) The variantId of the requested asset. If no variantId is used for identifying the asset, leave out the Query parameter.
   */
  private static final String DRMTODAY_VARIANT_ID_PARAM = "variantId";
  /**
   * Debug purposes.
   */
  private static final String DRMTODAY_LOG_REQUEST_ID_PARAM = "logRequestId";
  /**
   * All DRM Today calls use a random request Id that helps checking the request on the server.
   * Print this request id on all logs when possible.
   * This parameter should be generated and not manually set.
   */
  private static final int REQUEST_ID_SIZE = 16;

  private static final int MAX_MANUAL_REDIRECTS = 5;

  private final HttpDataSource.Factory dataSourceFactory;
  @Nullable private final String defaultLicenseUrl;
  private final boolean forceDefaultLicenseUrl;
  private final Map<String, String> keyRequestProperties;

  /**
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL. May be {@code null} if it's known that all key requests will specify
   *     their own URLs.
   * @param dataSourceFactory A factory from which to obtain {@link HttpDataSource} instances.
   */
  public DrmTodayWidevineCallback(
      @Nullable String defaultLicenseUrl, HttpDataSource.Factory dataSourceFactory) {
    this(defaultLicenseUrl, /* forceDefaultLicenseUrl= */ false, dataSourceFactory);
  }

  /**
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL, or for all key requests if {@code forceDefaultLicenseUrl} is set to
   *     true. May be {@code null} if {@code forceDefaultLicenseUrl} is {@code false} and if it's
   *     known that all key requests will specify their own URLs.
   * @param forceDefaultLicenseUrl Whether to force use of {@code defaultLicenseUrl} for key
   *     requests that include their own license URL.
   * @param dataSourceFactory A factory from which to obtain {@link HttpDataSource} instances.
   */
  public DrmTodayWidevineCallback(
      @Nullable String defaultLicenseUrl,
      boolean forceDefaultLicenseUrl,
      HttpDataSource.Factory dataSourceFactory) {
    Assertions.checkArgument(!(forceDefaultLicenseUrl && TextUtils.isEmpty(defaultLicenseUrl)));
    this.dataSourceFactory = dataSourceFactory;
    this.defaultLicenseUrl = defaultLicenseUrl;
    this.forceDefaultLicenseUrl = forceDefaultLicenseUrl;
    this.keyRequestProperties = new HashMap<>();
  }

  /**
   * Sets a header for key requests made by the callback.
   *
   * @param name The name of the header field.
   * @param value The value of the field.
   */
  public void setKeyRequestProperty(String name, String value) {
    Assertions.checkNotNull(name);
    Assertions.checkNotNull(value);
    synchronized (keyRequestProperties) {
      keyRequestProperties.put(name, value);
    }
  }

  /**
   * Clears a header for key requests made by the callback.
   *
   * @param name The name of the header field.
   */
  public void clearKeyRequestProperty(String name) {
    Assertions.checkNotNull(name);
    synchronized (keyRequestProperties) {
      keyRequestProperties.remove(name);
    }
  }

  /**
   * Clears all headers for key requests made by the callback.
   */
  public void clearAllKeyRequestProperties() {
    synchronized (keyRequestProperties) {
      keyRequestProperties.clear();
    }
  }

  @Override
  public byte[] executeProvisionRequest(UUID uuid, ExoMediaDrm.ProvisionRequest request)
      throws MediaDrmCallbackException {
    String url =
        request.getDefaultUrl() + "&signedRequest=" + Util.fromUtf8Bytes(request.getData());
    return executePost(
        dataSourceFactory,
        url,
        /* httpBody= */ null,
        /* requestProperties= */ Collections.emptyMap());
  }

  @Override
  public byte[] executeKeyRequest(UUID uuid, ExoMediaDrm.KeyRequest request) throws MediaDrmCallbackException {
    String url = request.getLicenseServerUrl();
    if (forceDefaultLicenseUrl || TextUtils.isEmpty(url)) {
      url = defaultLicenseUrl;
    }
    if (TextUtils.isEmpty(url)) {
      throw new MediaDrmCallbackException(
          new DataSpec.Builder().setUri(Uri.EMPTY).build(),
          Uri.EMPTY,
          /* responseHeaders= */ ImmutableMap.of(),
          /* bytesLoaded= */ 0,
          /* cause= */ new IllegalStateException("No license URL"));
    }
    Map<String, String> requestProperties = new HashMap<>();
    // Add standard request properties for supported schemes.
    String contentType = C.PLAYREADY_UUID.equals(uuid) ? "text/xml"
        : (C.CLEARKEY_UUID.equals(uuid) ? "application/json" : "application/octet-stream");
    requestProperties.put("Content-Type", contentType);
    if (C.PLAYREADY_UUID.equals(uuid)) {
      requestProperties.put("SOAPAction",
          "http://schemas.microsoft.com/DRM/2007/03/protocols/AcquireLicense");
    }
    // Add additional request properties.
    synchronized (keyRequestProperties) {
      requestProperties.putAll(keyRequestProperties);
    }
    final byte[] bytes;
    try {
      bytes = executePost(dataSourceFactory, url, request.getData(), requestProperties);
    } catch (MediaDrmCallbackException e) {
      throw e;
    }

    try {
      JSONObject jsonObject = new JSONObject(new String(bytes));
      return Base64.decode(jsonObject.getString("license"), Base64.DEFAULT);
    } catch (JSONException e) {
      throw new RuntimeException("Error while parsing response", e);
    }
  }

  private static byte[] executePost(
      HttpDataSource.Factory dataSourceFactory,
      String url,
      @Nullable byte[] httpBody,
      Map<String, String> requestProperties)
      throws MediaDrmCallbackException {
    StatsDataSource dataSource = new StatsDataSource(dataSourceFactory.createDataSource());
    int manualRedirectCount = 0;
    DataSpec dataSpec =
        new DataSpec.Builder()
            .setUri(url)
            .setHttpRequestHeaders(requestProperties)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(httpBody)
            .setFlags(DataSpec.FLAG_ALLOW_GZIP)
            .build();
    DataSpec originalDataSpec = dataSpec;
    try {
      while (true) {
        DataSourceInputStream inputStream = new DataSourceInputStream(dataSource, dataSpec);
        try {
          return Util.toByteArray(inputStream);
        } catch (HttpDataSource.InvalidResponseCodeException e) {
          @Nullable String redirectUrl = getRedirectUrl(e, manualRedirectCount);
          if (redirectUrl == null) {
            throw e;
          }
          manualRedirectCount++;
          dataSpec = dataSpec.buildUpon().setUri(redirectUrl).build();
        } finally {
          Util.closeQuietly(inputStream);
        }
      }
    } catch (Exception e) {
      throw new MediaDrmCallbackException(
          originalDataSpec,
          Assertions.checkNotNull(dataSource.getLastOpenedUri()),
          dataSource.getResponseHeaders(),
          dataSource.getBytesRead(),
          /* cause= */ e);
    }
  }

  @Nullable
  private static String getRedirectUrl(
      HttpDataSource.InvalidResponseCodeException exception, int manualRedirectCount) {
    // For POST requests, the underlying network stack will not normally follow 307 or 308
    // redirects automatically. Do so manually here.
    boolean manuallyRedirect =
        (exception.responseCode == 307 || exception.responseCode == 308)
            && manualRedirectCount < MAX_MANUAL_REDIRECTS;
    if (!manuallyRedirect) {
      return null;
    }
    Map<String, List<String>> headerFields = exception.headerFields;
    @Nullable List<String> locationHeaders = headerFields.get("Location");
    if (locationHeaders != null && !locationHeaders.isEmpty()) {
      return locationHeaders.get(0);
    }
    return null;
  }
}
