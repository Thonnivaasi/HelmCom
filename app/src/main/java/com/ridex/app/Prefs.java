package com.ridex.app;
import android.content.Context;
import android.content.SharedPreferences;
public class Prefs {
    private static final String NAME     = "ridex_prefs";
    private static final String KEY_USER = "username";
    private static final String KEY_IP   = "last_ip";
    private static final String KEY_CODE = "last_code";
    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(NAME, Context.MODE_PRIVATE); }
    public static void saveUsername(Context c, String v) { sp(c).edit().putString(KEY_USER, v).apply(); }
    public static String getUsername(Context c)          { return sp(c).getString(KEY_USER, "User"); }
    public static void saveLastIp(Context c, String v)   { sp(c).edit().putString(KEY_IP, v).apply(); }
    public static String getLastIp(Context c)            { return sp(c).getString(KEY_IP, null); }
    public static void saveLastCode(Context c, String v) { sp(c).edit().putString(KEY_CODE, v).apply(); }
    public static String getLastCode(Context c)          { return sp(c).getString(KEY_CODE, null); }
}
