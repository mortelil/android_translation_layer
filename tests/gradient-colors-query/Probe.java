// SPDX-License-Identifier: GPL-3.0-only
package org.atl.gradientcolors;
import android.app.Activity;import android.os.Bundle;import android.graphics.Color;import android.graphics.drawable.GradientDrawable;import java.util.Arrays;
public class Probe extends Activity {
 int checks;void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 public void onCreate(Bundle b){super.onCreate(b);int result=0;try{
 GradientDrawable g=new GradientDrawable();check(g.getColors()==null,"default solid");
 int[] colors={Color.RED,Color.GREEN,Color.BLUE};g.setColors(colors);
 check(Arrays.equals(g.getColors(),colors),"set colors");
 int[] first=g.getColors();check(first!=g.getColors(),"fresh arrays");first[0]=Color.BLACK;
 check(g.getColors()[0]==Color.RED,"query cannot mutate state");
 GradientDrawable made=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,colors);
 check(Arrays.equals(made.getColors(),colors),"constructor colors");
 GradientDrawable clone=(GradientDrawable)g.getConstantState().newDrawable().mutate();
 clone.setColors(new int[]{Color.BLACK,Color.WHITE});check(Arrays.equals(g.getColors(),colors),"mutated clone isolation");
 g.setColor(Color.RED);check(g.getColors()==null,"solid clears gradient");
 made.setColors(null);check(made.getColors()==null,"null clears gradient");
 System.out.println("GRADIENT_COLORS_PASS checks="+checks);android.util.Log.i("GradientColorsProbe","GRADIENT_COLORS_PASS checks="+checks);
 }catch(Throwable t){result=1;t.printStackTrace();android.util.Log.e("GradientColorsProbe","FAIL "+t);}finish();System.exit(result);}
}
