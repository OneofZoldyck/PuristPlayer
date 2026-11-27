# Purist Player (Just Player MOD)

Purist Player is a modified build of [Just Player](https://github.com/moneytoo/Player), the open source Android video player based on AndroidX Media3. It is for people who watch Dolby Vision and HDR files on their phone and want to see what the decoder is doing. It also adds the subtitle controls that Just Player lacks.

The app installs next to the original Just Player. Its package name is `com.brouken.player.purist`, and it is based on Just Player 0.221 with Media3 1.12.0-rc01.

## Screenshots

![Dead-End Job S01E07 playing in Dolby Vision with the format badge and the Recheck detail line](screenshots/dead-end-job-dolby-vision.jpg)

Dead-End Job S01E07. The Dolby Vision badge and the detail line come from Recheck video format in the gear menu. The subtitle uses a custom colour and background.

![Courage the Cowardly Dog S01 E01-E02 with the subtitle settings panel open](screenshots/courage-subtitle-settings.jpg)

Courage the Cowardly Dog S01 E01-E02. The subtitle settings panel is open during playback, with a live preview line.

## Modifications

| Feature | What it does | Notes |
|---|---|---|
| Format badge | Shows a Dolby Vision, HDR10+, HDR10, HDR or SDR logo in the top right corner for 3 seconds when a video starts | Based on how the hardware decoder is configured, so it catches Dolby Vision files that fall back to their HDR10 base layer |
| Recheck video format | Gear menu item that checks the format again and shows the logo with a detail line | The line lists the Dolby Vision profile, an estimate of the panel brightness in nits, the decoder and the HDR/SDR ratio. A converted profile 7 file shows as 7 FEL or 7 MEL → 8.1 |
| Dolby Vision profile 7 | Blu-ray profile 7 video is converted to profile 8.1 while it plays, so the phone's Dolby Vision decoder plays it instead of the HDR10 base layer | Automatic, with no setting. The enhancement layer is dropped and the dynamic metadata is kept, the same conversion Kodi uses. Works with FEL and MEL, in MKV and MP4, and in files muxed without the Dolby Vision mapping. Files that keep the Dolby Vision layer in a separate track or stream, such as Blu-ray m2ts, still play as HDR10 |
| Incomplete MKV files | Partly downloaded MKV files play up to the point where the download stopped, with seeking | The missing index is rebuilt from the downloaded part. The first open takes a few seconds and the index is saved for the next time. A message shows how much of the video can be played |
| Subtitle delay | Moves subtitles earlier or later in 50 ms and 500 ms steps | Works on every subtitle track and is saved for each video |
| Subtitle style | Size, text colour, edge type and colour, background colour and opacity, vertical position | Available in a panel during playback and in Settings. The style carries over to every video |
| Extra text formats | SAMI, MicroDVD, MPL2, SubViewer, SBV and TMPlayer files | The format is detected from the file name and content |
| Full ASS/SSA rendering | ASS subtitles are drawn by libass with their fonts, positions and karaoke effects | Fonts attached to MKV files are used. A Settings switch turns it off |
| Image subtitle files | Plays PGS `.sup` files and VobSub `.idx` with `.sub` files | DVD subtitle timing follows the DVD specification. Media3 ends these subtitles about 12% early |
| Size for every format | The size setting also scales ASS dialogue and PGS, VobSub and DVB images | ASS signs placed at fixed positions keep their size, so they stay on the picture |
| App identity | New name, icon and package name | Debug builds use their own package and install alongside |

## Video path

The video path is the same as in Just Player for every file except Dolby Vision profile 7. Video is decoded by the phone's hardware decoder and goes straight to the display. For profile 7 the app removes the enhancement layer and rewrites the Dolby Vision metadata of each frame before the frame reaches the decoder. The picture data of the base layer is not changed. Subtitles, including libass and image subtitles, are drawn in a separate layer above the video and never pass through the video frames. Dolby Vision decoding and the HDR brightness boost were checked with each change on a MediaTek phone with a Dolby Vision decoder.

## How to use it

The format badge appears by itself when a video starts. Open the gear menu and pick Recheck video format to see it again with the detail line.

Dolby Vision profile 7 files and incomplete MKV files need nothing from you. They are handled when they open.

To change subtitles, long press the subtitle button or pick Subtitle settings in the gear menu. To load a subtitle file, long press the folder button. For VobSub, select the `.idx` and the `.sub` file together. If you pick only one of them, the app looks for the other one in the same folder and asks for it when it cannot find it.

## Building

```
./gradlew :app:assembleLatestUniversalRelease -PabiFilter=arm64-v8a
```

Release signing reads a `keystore.properties` file in the project root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. The file is not part of the repository. Without it the release APK is built unsigned.

## Credits and license

Purist Player is based on [Just Player](https://github.com/moneytoo/Player) and uses [AndroidX Media3](https://github.com/androidx/media) and [libass-android](https://github.com/peerless2012/libass-android). The Dolby Vision profile 7 conversion is a Java port of part of [dovi_tool](https://github.com/quietvoid/dovi_tool) by quietvoid (MIT license, notice in `DoviRpu.java`) and follows the approach used by [Kodi](https://github.com/xbmc/xbmc). It is released under the GNU General Public License v3.0, the same license as Just Player. See [LICENSE](LICENSE).
