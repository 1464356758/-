package com.cameraprofile.studio;

import java.time.*;
import java.time.format.DateTimeFormatter;
import org.json.*;

/** Frozen once per job. Later UI edits never change an already queued export. */
public final class ExportSettings {
  public String profileId = "iphone18promax", mode = "rebuild", timeMode = "current";
  public int lens = 0, resolution = 0, quality = 98, strength = 25;
  public boolean detail = true, ai = false;
  public String customDate = "", customZone = "+08:00";
  public Integer iso, whiteBalance;
  public Double exposure, bias;

  public JSONObject json() throws JSONException {
    JSONObject out = new JSONObject();
    out.put("profile_id", profileId).put("lens", lens).put("resolution", resolution);
    out.put("mode", mode).put("time_mode", timeMode).put("quality", quality);
    out.put("detail", detail).put("ai", ai).put("strength", strength);
    out.put("custom_date", customDate).put("custom_zone", customZone);
    if (iso != null) out.put("iso", iso);
    if (whiteBalance != null) out.put("white_balance", whiteBalance);
    if (exposure != null) out.put("exposure", exposure);
    if (bias != null) out.put("bias", bias);
    return out;
  }

  public static ExportSettings parse(JSONObject json) {
    ExportSettings out = new ExportSettings();
    out.profileId = json.optString("profile_id", out.profileId);
    out.lens = json.optInt("lens");
    out.resolution = json.optInt("resolution");
    out.mode = json.optString("mode", "rebuild");
    out.timeMode = json.optString("time_mode", "current");
    out.quality = json.optInt("quality", 98);
    out.detail = json.optBoolean("detail", true);
    out.ai = json.optBoolean("ai");
    out.strength = json.optInt("strength", 25);
    out.customDate = json.optString("custom_date", "");
    out.customZone = json.optString("custom_zone", "+08:00");
    if (json.has("iso")) out.iso = json.optInt("iso");
    if (json.has("exposure")) out.exposure = json.optDouble("exposure");
    if (json.has("bias")) out.bias = json.optDouble("bias");
    if (json.has("white_balance")) out.whiteBalance = json.optInt("white_balance");
    return out;
  }

  public String[] time() {
    if ("none".equals(timeMode)) return new String[] {null, null};
    OffsetDateTime stamp;
    if ("custom".equals(timeMode)) {
      LocalDateTime local =
          LocalDateTime.parse(
              customDate,
              DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                  .withResolverStyle(java.time.format.ResolverStyle.STRICT));
      if (local.getYear() < 1 || local.getYear() > 9999)
        throw new IllegalArgumentException("年份应在 1–9999 之间");
      if (!customZone.matches("[+-]\\d{2}:\\d{2}"))
        throw new IllegalArgumentException("时区请使用 +08:00 这样的时:分格式");
      ZoneOffset zone = ZoneOffset.of(customZone);
      if (Math.abs(zone.getTotalSeconds()) > 14 * 3600)
        throw new IllegalArgumentException("时区超出 ±14 小时");
      stamp = local.atOffset(zone);
    } else if ("current".equals(timeMode)) stamp = OffsetDateTime.now();
    else throw new IllegalArgumentException("时间模式无效");
    String offset = stamp.getOffset().getId();
    if ("Z".equals(offset)) offset = "+00:00";
    return new String[] {stamp.format(DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss")), offset};
  }
}
