// SPDX-License-Identifier: GPL-3.0-only
package org.atl.videocapabilities;
import android.app.Activity;
import android.os.Bundle;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
public class Probe extends Activity {
 int checks;
 void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
 public void onCreate(Bundle state){super.onCreate(state);int result=0;try{
  int codecs=0;
  for(MediaCodecInfo info:new MediaCodecList(0).getCodecInfos()) {
   if(info.isEncoder())continue;
   for(String mime:info.getSupportedTypes()) {
    if(!mime.startsWith("video/"))continue;
    MediaCodecInfo.VideoCapabilities c=info.getCapabilitiesForType(mime).getVideoCapabilities();
    check(c!=null,"video caps");
    check(c.getSupportedWidths().getLower()>0,"positive width");
    check(c.getSupportedHeights().getLower()>0,"positive height");
    check(!c.isSizeSupported(0,1080),"zero width");
    check(!c.isSizeSupported(1920,-1),"negative height");
    check(!c.areSizeAndRateSupported(1920,1080,Double.POSITIVE_INFINITY),"infinite rate");
    boolean threw=false;try{c.getSupportedHeightsFor(0);}catch(IllegalArgumentException e){threw=true;}check(threw,"invalid dependent height range");
    threw=false;try{c.getSupportedWidthsFor(0);}catch(IllegalArgumentException e){threw=true;}check(threw,"invalid dependent width range");
    threw=false;try{c.getSupportedFrameRatesFor(0,0);}catch(IllegalArgumentException e){threw=true;}check(threw,"invalid rate size");
    if(c.isSizeSupported(1920,1080)) {
     check(c.getSupportedHeightsFor(1920).contains(1080),"dependent heights");
     check(c.getSupportedWidthsFor(1080).contains(1920),"dependent widths");
     check(c.getSupportedFrameRatesFor(1920,1080).getUpper()<=c.getSupportedFrameRates().getUpper(),"rate bound");
    }
    codecs++;
   }
  }
  check(codecs>0,"video decoders present");
  android.util.Log.i("VideoCapsProbe","VIDEO_CAPS_PASS codecs="+codecs+" checks="+checks);
  System.out.println("VIDEO_CAPS_PASS codecs="+codecs+" checks="+checks);
 }catch(Throwable t){result=1;t.printStackTrace();android.util.Log.e("VideoCapsProbe","FAIL "+t);}finish();System.exit(result);}
}
