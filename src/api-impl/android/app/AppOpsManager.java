/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class AppOpsManager {
	public static final int MODE_ALLOWED = 0;
	public static final int MODE_IGNORED = 1;
	public static final int MODE_ERRORED = 2;
	public static final int MODE_DEFAULT = 3;
	public static final int MODE_FOREGROUND = 4;

	public static final String OPSTR_GET_USAGE_STATS = "android:get_usage_stats";

	// Operation names and mode values from AOSP frameworks/base commit
	// 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d (Android 17), AppOpsManager.java.
	// This is vocabulary, not a claim that ATL implements these capabilities.
	private static final Set<String> KNOWN_OPS = new HashSet<>(Arrays.asList(
		"android:coarse_location", "android:fine_location", "android:monitor_location",
		"android:monitor_location_high_power", "android:get_usage_stats", "android:activate_vpn",
		"android:read_contacts", "android:write_contacts", "android:read_call_log",
		"android:write_call_log", "android:read_calendar", "android:write_calendar",
		"android:call_phone", "android:read_sms", "android:receive_sms",
		"android:receive_mms", "android:receive_wap_push", "android:send_sms",
		"android:camera", "android:record_audio", "android:read_phone_state",
		"android:add_voicemail", "android:use_sip", "android:process_outgoing_calls",
		"android:use_fingerprint", "android:body_sensors", "android:read_cell_broadcasts",
		"android:mock_location", "android:read_external_storage", "android:write_external_storage",
		"android:system_alert_window", "android:write_settings", "android:get_accounts",
		"android:read_phone_numbers", "android:picture_in_picture", "android:instant_app_start_foreground",
		"android:answer_phone_calls", "android:accept_handover", "android:gps",
		"android:vibrate", "android:wifi_scan", "android:post_notification",
		"android:neighboring_cells", "android:write_sms", "android:receive_emergency_broadcast",
		"android:read_icc_sms", "android:write_icc_sms", "android:access_notifications",
		"android:play_audio", "android:read_clipboard", "android:write_clipboard",
		"android:take_media_buttons", "android:take_audio_focus", "android:audio_master_volume",
		"android:audio_voice_volume", "android:audio_ring_volume", "android:audio_media_volume",
		"android:audio_alarm_volume", "android:audio_notification_volume", "android:audio_bluetooth_volume",
		"android:wake_lock", "android:mute_microphone", "android:toast_window",
		"android:project_media", "android:write_wallpaper", "android:assist_structure",
		"android:assist_screenshot", "android:turn_screen_on", "android:run_in_background",
		"android:audio_accessibility_volume", "android:request_install_packages", "android:run_any_in_background",
		"android:change_wifi_state", "android:request_delete_packages", "android:bind_accessibility_service",
		"android:manage_ipsec_tunnels", "android:start_foreground", "android:bluetooth_scan",
		"android:bluetooth_connect", "android:bluetooth_advertise", "android:use_biometric",
		"android:activity_recognition", "android:sms_financial_transactions", "android:read_media_audio",
		"android:write_media_audio", "android:read_media_video", "android:write_media_video",
		"android:read_media_images", "android:write_media_images", "android:legacy_storage",
		"android:access_media_location", "android:access_accessibility", "android:read_device_identifiers",
		"android:query_all_packages", "android:manage_external_storage", "android:auto_revoke_permissions_if_unused",
		"android:auto_revoke_managed_by_installer", "android:interact_across_profiles", "android:activate_platform_vpn",
		"android:loader_usage_stats", "android:manage_ongoing_calls", "android:media_routing_control",
		"android:archive_icon_overlay", "android:unarchival_support", "android:no_isolated_storage",
		"android:phone_call_microphone", "android:phone_call_camera", "android:record_audio_hotword",
		"android:manage_credentials", "android:use_icc_auth_with_device_identifier", "android:record_audio_output",
		"android:schedule_exact_alarm", "android:fine_location_source", "android:coarse_location_source",
		"android:camera_sandboxed", "android:record_audio_sandboxed", "android:manage_media",
		"android:uwb_ranging", "android:nearby_wifi_devices", "android:activity_recognition_source",
		"android:record_incoming_phone_audio", "android:establish_vpn_service", "android:establish_vpn_manager",
		"android:access_restricted_settings", "android:receive_ambient_trigger_audio", "android:read_media_visual_user_selected",
		"android:read_write_health_data", "android:receive_explicit_user_interaction_audio", "android:run_user_initiated_jobs",
		"android:system_exempt_from_suspension", "android:system_exempt_from_dismissible_notifications", "android:foreground_service_special_use",
		"android:system_exempt_from_power_restrictions", "android:system_exempt_from_hibernation", "android:system_exempt_from_activity_bg_start_restriction",
		"android:capture_consentless_bugreport_on_userdebug_build", "android:deprecated_2", "android:use_full_screen_intent",
		"android:receive_sandbox_trigger_audio", "android:deprecated_3", "android:create_accessibility_overlay",
		"android:enable_mobile_data_by_user", "android:reserved_for_testing", "android:rapid_clear_notifications_by_listener",
		"android:read_system_grammatical_gender", "android:deprecated_4", "android:emergency_location",
		"android:receive_sensitive_notifications", "android:read_heart_rate", "android:read_oxygen_saturation",
		"android:read_skin_temperature", "android:ranging", "android:write_system_preferences",
		"android:control_audio", "android:control_audio_partial", "android:eye_tracking_coarse",
		"android:eye_tracking_fine", "android:face_tracking", "android:hand_tracking",
		"android:head_tracking", "android:scene_understanding_coarse", "android:scene_understanding_fine",
		"android:post_promoted_notifications", "android:system_application_overlay", "android:read_cell_identity",
		"android:read_cell_info", "android:computer_control", "android:read_otp_sms",
		"android:access_local_network", "android:read_blood_pressure", "android:read_heart_rate_variability",
		"android:read_respiratory_rate", "android:read_vo2_max", "android:continue_across_devices",
		"android:read_screen_context", "android:read_restricted_messages", "android:write_restricted_messages",
		"android:access_hid"
	));

	private final Context context;

	public AppOpsManager() {
		this(null);
	}

	public AppOpsManager(Context context) {
		this.context = context;
	}

	/**
	 * Read-only capability policy for the owning ATL app. Unlike Android's
	 * AppOpsService, ATL has no persisted per-operation modes or UID-state
	 * restrictions. Only capabilities backed by its permission checks can be
	 * allowed; the rest are ignored, never advertised as available.
	 *
	 * As in AOSP, NoThrow suppresses policy/security failures, not an invalid
	 * operation name. This query neither records usage nor starts an operation.
	 */
	public int checkOpNoThrow(String op, int uid, String packageName) {
		if (!KNOWN_OPS.contains(op))
			throw new IllegalArgumentException("Unknown operation string: " + op);
		// Linux UIDs are shared by ATL apps. Matching the UID alone is not enough.
		if (context == null || uid != Process.myUid() ||
				!context.getPackageName().equals(packageName))
			return MODE_ERRORED;

		String permission;
		switch (op) {
			case "android:coarse_location":
				permission = "android.permission.ACCESS_COARSE_LOCATION";
				break;
			case "android:fine_location":
				permission = "android.permission.ACCESS_FINE_LOCATION";
				break;
			case "android:record_audio":
				permission = "android.permission.RECORD_AUDIO";
				break;
			case "android:read_external_storage":
				permission = "android.permission.READ_EXTERNAL_STORAGE";
				break;
			case "android:write_external_storage":
				permission = "android.permission.WRITE_EXTERNAL_STORAGE";
				break;
			default:
				// In particular, there is no usage-stats, overlay, or PiP backend.
				return MODE_IGNORED;
		}
		return context.getPackageManager().checkPermission(permission, packageName)
				== PackageManager.PERMISSION_GRANTED ? MODE_ALLOWED : MODE_IGNORED;
	}

	// Legacy note/proxy and permission mapping behavior is outside the query
	// implementation above. It does not constitute AppOps enforcement.
	public static String permissionToOp(String permission) {
		return permission;
	}

	public int noteProxyOpNoThrow(String op, String pkg) {
		return 0;
	}
}
