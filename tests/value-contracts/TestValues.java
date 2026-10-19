// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.values;
import android.animation.Keyframe;
import android.media.*;
import android.view.InputDevice;
import java.lang.reflect.*;
public class TestValues {
 static int checks;
 static void check(boolean ok,String why) { checks++; if(!ok) throw new AssertionError(why); }
 interface Op { void run() throws Exception; }
 static void rejects(Op op) throws Exception { try { op.run(); throw new AssertionError("accepted invalid argument"); } catch(IllegalArgumentException expected) {checks++;} }
 static void frames() {
  Keyframe i=Keyframe.ofInt(.25f); check(i.getType()==int.class,"int type"); check(!i.hasValue(),"unset int"); check(i.getValue().equals(0),"default int");
  i.setValue(2f); check(!i.hasValue(),"float must not initialize int"); i.setValue(7); check(i.hasValue()&&i.getValue().equals(7),"int assignment"); i.setValue(null); check(i.getValue().equals(7),"null ignored");
  android.animation.TimeInterpolator interp=new android.animation.TimeInterpolator(){public float getInterpolation(float x){return x*x;}};
  i.setInterpolator(interp); Keyframe c=i.clone(); check(c!=i&&c.getValue().equals(7)&&c.getFraction()==.25f&&c.getInterpolator()==interp,"clone metadata"); c.setValue(8); check(i.getValue().equals(7),"clone independent");
  Keyframe f=Keyframe.ofFloat(.5f); check(f.getType()==float.class&&!f.hasValue()&&f.getValue().equals(0f),"float placeholder"); f.setValue(4); check(!f.hasValue(),"int does not initialize float"); f.setValue(4f); check(f.hasValue()&&f.getValue().equals(4f),"float assignment");
  check(Keyframe.ofInt(1,3).getValue().equals(3),"int factory"); check(Keyframe.ofFloat(1,3f).getValue().equals(3f),"float factory");
  Keyframe o=Keyframe.ofObject(0); check(o.getType()==Object.class&&!o.hasValue(),"object placeholder"); o.setValue("value"); check(o.hasValue()&&o.getValue().equals("value"),"object assignment");
  check(!Keyframe.ofInt(0).clone().hasValue(),"unset clone remains unset");
 }
 static void audio() throws Exception {
  AudioFormat a=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_DEFAULT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
  check(a.getEncoding()==AudioFormat.ENCODING_PCM_16BIT&&a.getSampleRate()==48000&&a.getChannelCount()==2,"format fields");
  AudioFormat b=new AudioFormat.Builder(a).build(); check(a.equals(b)&&a.hashCode()==b.hashCode(),"copy equality");
  check(!a.equals(new AudioFormat.Builder(a).setSampleRate(44100).build()),"different rate");
  check(new AudioFormat.Builder().build().getEncoding()==AudioFormat.ENCODING_INVALID,"unspecified encoding");
  check(new AudioFormat.Builder().setChannelIndexMask(3).build().getChannelCount()==2,"index count");
  rejects(new Op(){public void run(){new AudioFormat.Builder().setChannelMask(0);}}); rejects(new Op(){public void run(){new AudioFormat.Builder().setChannelIndexMask(0);}});
  rejects(new Op(){public void run(){new AudioFormat.Builder().setSampleRate(-1);}}); rejects(new Op(){public void run(){new AudioFormat.Builder().setEncoding(-999);}});
  rejects(new Op(){public void run(){new AudioFormat.Builder().setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setChannelIndexMask(3);}});
  rejects(new Op(){public void run(){new AudioFormat.Builder().setChannelIndexMask(3).setChannelMask(AudioFormat.CHANNEL_OUT_MONO);}});
  try {new AudioFormat(); throw new AssertionError("legacy constructor accepted");} catch(UnsupportedOperationException expected){checks++;}
  for(Class<?> type:new Class<?>[]{AudioTrack.class,AudioRecord.Builder.class}) {
   Method m=type.getDeclaredMethod(type==AudioTrack.class?"channelConfigForNative":"channelConfig",AudioFormat.class);m.setAccessible(true);
   check(((Integer)m.invoke(null,new AudioFormat.Builder().setChannelIndexMask(1).build()))==(type==AudioTrack.class?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_IN_MONO),"mono index translation");
   check(((Integer)m.invoke(null,new AudioFormat.Builder().setChannelIndexMask(3).build()))==(type==AudioTrack.class?AudioFormat.CHANNEL_OUT_STEREO:AudioFormat.CHANNEL_IN_STEREO),"stereo index translation");
   try{m.invoke(null,new AudioFormat.Builder().setChannelIndexMask(4).build());throw new AssertionError("sparse mask accepted");}catch(InvocationTargetException e){check(e.getCause() instanceof IllegalArgumentException,"sparse mask explicit failure");}
  }
 }
 static void input(){InputDevice d=InputDevice.getDevice(0);check(d.getKeyboardType()==InputDevice.KEYBOARD_TYPE_ALPHABETIC,"aggregate keyboard"); check(d.supportsSource(InputDevice.SOURCE_KEYBOARD),"keyboard source");check(d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN),"touch source");check(!d.supportsSource(0x01000010),"no invented joystick");}
 public static void main(String[] args)throws Exception {frames(); audio(); input(); System.out.println("PASS values checks="+checks);System.out.flush();Runtime.getRuntime().halt(0);}
}
