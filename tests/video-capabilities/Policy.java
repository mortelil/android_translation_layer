// SPDX-License-Identifier: GPL-3.0-only
import android.media.MediaCodecInfo;
public class Policy {
 static int checks;
 static void check(boolean value){if(!value)throw new AssertionError("check "+checks);checks++;}
 public static void main(String[] args){
  MediaCodecInfo.VideoCapabilities c=new MediaCodecInfo.VideoCapabilities();
  check(c.isSizeSupported(4096,2160));check(c.isSizeSupported(2160,4096));
  check(!c.isSizeSupported(4096,4096));check(!c.isSizeSupported(Integer.MAX_VALUE,Integer.MAX_VALUE));
  check(c.getSupportedHeightsFor(4096).getUpper()==2160);
  check(c.getSupportedWidthsFor(4096).getUpper()==2160);
  check(c.areSizeAndRateSupported(1920,1080,59.94));
  check(!c.areSizeAndRateSupported(1920,1080,60.01));
  check(!c.areSizeAndRateSupported(1920,1080,Double.NaN));
  check(!c.areSizeAndRateSupported(1920,1080,-1));
  check(c.getAchievableFrameRatesFor(1920,1080)==null);
  boolean threw=false;try{c.getSupportedFrameRatesFor(4096,4096);}catch(IllegalArgumentException e){threw=true;}check(threw);
  System.out.println("VIDEO_POLICY_PASS checks="+checks);
 }
}
