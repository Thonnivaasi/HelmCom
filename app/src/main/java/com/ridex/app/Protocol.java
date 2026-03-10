package com.ridex.app;
public class Protocol {
    public static final int PORT_DISCOVERY = 51000;
    public static final int PORT_VOICE     = 51001;
    public static final int PORT_MUSIC     = 51002;
    public static final int PORT_CONTROL   = 51003;
    public static final String CMD_HELLO       = "HELLO:";
    public static final String CMD_ACK         = "ACK:";
    public static final String CMD_PLAYLISTS   = "PLAYLISTS:";
    public static final String CMD_SELECT_PL   = "SELECT_PL:";
    public static final String CMD_PLAY        = "PLAY";
    public static final String CMD_PAUSE       = "PAUSE";
    public static final String CMD_NEXT        = "NEXT";
    public static final String CMD_PREV        = "PREV";
    public static final String CMD_SELECT_SONG = "SELECT_SONG:";
    public static final String CMD_NOW_PLAYING = "NOW_PLAYING:";
    public static final String CMD_MUSIC_VOL   = "MUSIC_VOL:";
    public static final String CMD_BYE         = "BYE";
}
