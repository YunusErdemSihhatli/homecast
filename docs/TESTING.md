# HomeCast on-device test checklist

This checklist is for testing HomeCast at home with a real phone and a real TV.
It does not need any developer tools. Tick each box as you go, and write down
anything that goes wrong (what you did, what you expected, what happened). If
something fails, please open a bug report with those notes.

## Before you start

You need:

- The phone with HomeCast installed (from the `HomeCast-vX.Y.Z.apk` file).
- The TV (or TV box) with Chromecast built in, switched on.
- Both on **the same Wi-Fi network**.
- A few web pages you normally watch videos on: one film, one series with at
  least two episodes, one page with subtitles, and one live stream.
- A Turkish `.srt` subtitle file saved on the phone (an older one is best:
  those are usually in the Windows-1254 encoding and contain letters like
  ç, ğ, ı, İ, ö, ş, ü).
- About an hour for the long test in section 4.

First launch:

- [ ] HomeCast asks to **keep playing with the screen off**. Tap **Open
      settings** and allow it (on Samsung: choose *Allow* / *Unrestricted*).
- [ ] If Android asks for permission to show notifications, allow it.
- [ ] Samsung only: in *Settings → Battery → Background usage limits*, make sure
      HomeCast is **not** listed under *Sleeping apps* or *Deep sleeping apps*.

## 1. Find the TV

- [ ] Tap the Cast icon (top right) in the browser, then **Choose TV**. Your TV
      appears under "Looking for TVs on this Wi-Fi…" within about 10 seconds.
- [ ] Tap the TV. The button at the top now shows the TV's name.
- [ ] Write down the TV's IP address (TV settings → Network, or your router).
      Long-press the TV in the list and choose **Forget**, then type the IP
      address in **Connect by IP** and tap **Connect**. It connects.
- [ ] Restart the TV (unplug it for 10 seconds). When it is back, open
      HomeCast's TV list and tap the saved TV. It connects again.
- [ ] If your router lets you, give the TV a different IP address (or wait until
      it gets one). Tap a TV that HomeCast found in the list (not one typed in by
      IP): within about 5 seconds HomeCast finds it at the new address, connects,
      and the list shows the new address. A TV typed in by IP instead says it
      couldn't connect; typing the new IP address connects.

## 2. Play a film

- [ ] Open a page with a film. Start the video in the page's player.
- [ ] A bar appears at the bottom: **Video found · …** with **Play on TV**. (If
      there is a **Choose** button, the page has several videos; pick the one
      that looks like the film.)
- [ ] Tap **Play on TV**. The remote screen opens and the film starts on the TV
      within about 15 seconds.
- [ ] The phone stays silent while the TV plays: the film is paused in the
      browser page (go **Back to browser** to check).
- [ ] **Pause** and **Play** work, and the TV reacts within about a second.
- [ ] **−10 s** and **+10 s** jump back and forward.
- [ ] Drag the **seek bar** to the middle of the film. The TV jumps there and
      keeps playing.
- [ ] The **volume** slider and **mute** button change the TV's volume.
- [ ] With the remote screen open, the phone's **volume buttons** change the
      TV's volume (not the phone's).
- [ ] Tap **Stop**. The video stops on the TV, and the HomeCast notification
      goes away.
- [ ] Play a film again, then close it on the TV itself (TV remote: Back or
      Home). Within a few seconds the HomeCast notification changes to
      **Stopped** and can be swiped away; the remote screen shows **Play again on
      TV**. Tap it: the film plays again on the TV from about where it was.
- [ ] Quick taps: tap **Play on TV**, then quickly **Stop**, then **Play on TV**
      again from the browser, then **Stop** again, as fast as you can. The app
      must not crash or freeze; the TV ends up stopped.

## 3. Notification and lock screen

Start a film playing on the TV again, then:

- [ ] Pull down the notification shade. A **HomeCast — Playing on [TV name]**
      media notification is there.
- [ ] Its **Pause/Play** button works.
- [ ] Android 13 or newer: the notification shows **Back 10 seconds**,
      **Forward 10 seconds** and **Stop** buttons, and each one works. (Open the
      media player in the quick settings panel if the buttons are hidden.)
- [ ] Lock the phone. The lock screen shows the media controls, and Pause/Play
      works from there.
- [ ] With the phone locked and the HomeCast screen not open, the phone's
      volume buttons still change the TV volume.

## 4. Long playback with the screen off

This is the most important test: the video goes through the phone, so if
Android stops HomeCast in the background, the TV stops.

- [ ] Start a film (or a long episode) on the TV.
- [ ] Lock the phone and leave it alone for **at least 30 minutes** (an hour is
      better). Don't plug it in, so battery saving behaves normally.
- [ ] The video plays on the TV the whole time, without stopping or freezing.
- [ ] Unlock the phone and open HomeCast. The remote screen shows the right
      position and the controls still work.

## 5. Subtitles

- [ ] Open a page whose player shows subtitles, turn the subtitles on in the
      page's player, then tap **Play on TV**.
- [ ] On the remote screen, the **Subtitles** menu lists the page's subtitles.
      Choose one: it appears on the TV, in sync with the speech.
- [ ] Choose **Off**: the subtitles disappear. Choose one again: they come back.
- [ ] Open the **Subtitles** menu and pick **Add .srt file…**. Choose your
      Turkish `.srt` file.
- [ ] The video continues from about the same position, with your subtitles on
      the TV.
- [ ] Turkish letters are correct on the TV: **ç ğ ı İ ö ş ü** (and capitals
      **Ç Ğ Ö Ş Ü**), with no `?`, boxes or strange symbols like `Ã§` or `ý`.

## 6. Series: next episode

- [ ] Play episode 1 of a series on the TV.
- [ ] Tap **Back to browser**. The episode page is still there (it does not
      reload or go back to the start page).
- [ ] Open episode 2 on the same site and start its player.
- [ ] Tap **Play on TV**. Episode 2 replaces episode 1 on the TV, with nothing
      shown in between (no ads, no other pages).
- [ ] The remote shows the new episode's title, and the controls work.

## 7. Live stream

- [ ] Open a page with a live stream (live TV channel or live event) and start
      its player.
- [ ] Tap **Play on TV**. It plays on the TV.
- [ ] The remote screen shows **LIVE** instead of the seek bar.
- [ ] Leave it playing for 10 minutes: it keeps playing (it does not stop after
      the first minute or loop the same part).

## 8. Pages with popups

- [ ] Open a page whose player opens popups or new tabs when you tap it (many
      free video pages do this).
- [ ] Tap the player a few times. No new window or other site opens, and you
      stay on the same page.
- [ ] Tap a normal link that would open in a new tab (for example an episode
      link). It opens in the same page, and **Back** returns to where you were.
- [ ] The video still plays in the page, and **Play on TV** still works.

## 9. Wi-Fi change during playback

- [ ] Start a video on the TV.
- [ ] On the phone, turn Wi-Fi off for about 10 seconds, then on again (the
      phone reconnects to the same Wi-Fi).
- [ ] Either the video keeps playing, or HomeCast shows a message. If the TV
      stopped, tap **Reconnect** on the remote if it shows, then **Play again on
      TV**: it plays again from about where it was. (Tapping **Play on TV** in
      the browser also works, from the start.)
- [ ] Turn Wi-Fi off and stay on mobile data. Tap **Play on TV**: HomeCast says
      **Connect your phone to the same Wi-Fi as the TV.** (It must not try to
      play.)
- [ ] If you have two Wi-Fi networks (e.g. 2.4 GHz and 5 GHz from the same
      router), switch the phone to the other one during playback, then tap
      **Play on TV** again. It plays.

## 10. Finishing

- [ ] Stop playback. The video stops on the TV and the notification disappears.
- [ ] Swipe HomeCast away from the recent apps, then open it again. It starts
      normally, and your TV is still in the saved list.

## Reporting a problem

Open a [bug report](https://github.com/YunusErdemSihhatli/homecast/issues/new/choose)
and include:

- the checklist step that failed,
- the phone model and Android version, and the TV model,
- what you expected and what happened,
- whether it happens every time.

Please don't include links to the pages you were watching; describe the kind of
page instead (for example "embedded player in an iframe", "live stream").
