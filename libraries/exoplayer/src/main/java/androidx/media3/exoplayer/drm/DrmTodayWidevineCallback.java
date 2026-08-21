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

import static androidx.media3.exoplayer.drm.DrmUtil.executePost;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest;
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A {@link MediaDrmCallback} that makes requests using {@link DataSource} instances, and unwraps
 * castLabs DRMtoday Widevine license responses.
 *
 * <p>DRMtoday returns Widevine licenses wrapped in a JSON envelope of the form {@code
 * {"license":"<base64>"}} rather than as raw license bytes. This callback transparently unwraps that
 * envelope. Responses that are not a DRMtoday envelope are passed through unchanged, so this
 * callback also works against standard Widevine license servers.
 */
@UnstableApi
public final class DrmTodayWidevineCallback implements MediaDrmCallback {

  /** The name of the JSON field holding the base64-encoded license in a DRMtoday response. */
  private static final String DRMTODAY_LICENSE_FIELD = "license";

  private final DataSource.Factory dataSourceFactory;
  @Nullable private final String defaultLicenseUrl;
  private final boolean forceDefaultLicenseUrl;
  private final Map<String, String> keyRequestProperties;

  /**
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL. May be {@code null} if it's known that all key requests will specify
   *     their own URLs.
   * @param dataSourceFactory A factory from which to obtain {@link DataSource} instances. This will
   *     usually be an HTTP-based {@link DataSource}.
   */
  public DrmTodayWidevineCallback(
      @Nullable String defaultLicenseUrl, DataSource.Factory dataSourceFactory) {
    this(defaultLicenseUrl, /* forceDefaultLicenseUrl= */ false, dataSourceFactory);
  }

  /**
   * @param defaultLicenseUrl The default license URL. Used for key requests that do not specify
   *     their own license URL, or for all key requests if {@code forceDefaultLicenseUrl} is set to
   *     true. May be {@code null} if {@code forceDefaultLicenseUrl} is {@code false} and if it's
   *     known that all key requests will specify their own URLs.
   * @param forceDefaultLicenseUrl Whether to force use of {@code defaultLicenseUrl} for key
   *     requests that include their own license URL.
   * @param dataSourceFactory A factory from which to obtain {@link DataSource} instances. This will
   *     usually be an HTTP-based {@link DataSource}.
   */
  public DrmTodayWidevineCallback(
      @Nullable String defaultLicenseUrl,
      boolean forceDefaultLicenseUrl,
      DataSource.Factory dataSourceFactory) {
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

  /** Clears all headers for key requests made by the callback. */
  public void clearAllKeyRequestProperties() {
    synchronized (keyRequestProperties) {
      keyRequestProperties.clear();
    }
  }

  @Override
  public Response executeProvisionRequest(UUID uuid, ProvisionRequest request)
      throws MediaDrmCallbackException {
    String url =
        request.getDefaultUrl() + "&signedRequest=" + Util.fromUtf8Bytes(request.getData());
    return executePost(
        dataSourceFactory.createDataSource(),
        url,
        /* httpBody= */ null,
        /* requestProperties= */ ImmutableMap.of());
  }

  @Override
  public Response executeKeyRequest(UUID uuid, KeyRequest request)
      throws MediaDrmCallbackException {
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
    String contentType =
        C.PLAYREADY_UUID.equals(uuid)
            ? "text/xml"
            : (C.CLEARKEY_UUID.equals(uuid) ? "application/json" : "application/octet-stream");
    requestProperties.put("Content-Type", contentType);
    if (C.PLAYREADY_UUID.equals(uuid)) {
      requestProperties.put(
          "SOAPAction", "http://schemas.microsoft.com/DRM/2007/03/protocols/AcquireLicense");
    }
    // Add additional request properties.
    synchronized (keyRequestProperties) {
      requestProperties.putAll(keyRequestProperties);
    }
    Response response =
        executePost(
            dataSourceFactory.createDataSource(),
            url,
            /* httpBody= */ request.getData(),
            requestProperties);
    if (!C.WIDEVINE_UUID.equals(uuid)) {
      // Only Widevine responses are wrapped in a DRMtoday envelope. PlayReady responses are XML and
      // ClearKey responses are already in the format expected by the framework.
      return response;
    }
    return maybeUnwrapDrmTodayLicense(response, url);
  }

  /**
   * Unwraps a DRMtoday {@code {"license":"<base64>"}} envelope, returning {@code response} unchanged
   * if it isn't such an envelope.
   *
   * @param response The response from the license server.
   * @param url The license URL the response was obtained from, used for error reporting.
   * @return A {@link Response} holding the raw license bytes.
   * @throws MediaDrmCallbackException If a DRMtoday envelope was found but its license could not be
   *     decoded.
   */
  private static Response maybeUnwrapDrmTodayLicense(Response response, String url)
      throws MediaDrmCallbackException {
    String licenseBase64;
    try {
      JSONObject jsonObject = new JSONObject(Util.fromUtf8Bytes(response.data));
      if (!jsonObject.has(DRMTODAY_LICENSE_FIELD)) {
        // A JSON body without a license field isn't a DRMtoday envelope. Pass it through.
        return response;
      }
      licenseBase64 = jsonObject.getString(DRMTODAY_LICENSE_FIELD);
    } catch (JSONException e) {
      // Not JSON at all, so these are raw license bytes from a non-DRMtoday license server.
      return response;
    }
    byte[] license;
    try {
      license = Base64.decode(licenseBase64, Base64.DEFAULT);
    } catch (IllegalArgumentException e) {
      throw new MediaDrmCallbackException(
          new DataSpec.Builder().setUri(url).build(),
          Uri.parse(url),
          /* responseHeaders= */ ImmutableMap.of(),
          /* bytesLoaded= */ response.data.length,
          /* cause= */ e);
    }
    Response.Builder builder = new Response.Builder(license);
    if (response.loadEventInfo != null) {
      builder.setLoadEventInfo(response.loadEventInfo);
    }
    return builder.build();
  }
}
