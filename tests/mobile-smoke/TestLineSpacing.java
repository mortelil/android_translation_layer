/* SPDX-License-Identifier: GPL-3.0-only */
import android.content.Context;
import android.text.*;
import android.text.style.AbsoluteSizeSpan;
import android.widget.TextView;
import android.widget.EditText;
import android.view.View;

public class TestLineSpacing {
	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
	private static long roundSigned(double value) { return value < 0 ? -Math.round(-value) : Math.round(value); }
	private static void compare(CharSequence text, TextPaint paint, float add, float mult) {
		Layout plain = new StaticLayout(text, paint, 300, Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
		Layout spaced = StaticLayout.Builder.obtain(text, 0, text.length(), paint, 300)
		    .setIncludePad(false).setLineSpacing(add, mult).build();
		int shift=0, count=plain.getLineCount();
		check(count==spaced.getLineCount(), "Spacing must not change wrapping");
		for (int i=0;i<count;i++) {
			check(spaced.getLineBaseline(i)==plain.getLineBaseline(i)+shift, "Baseline must follow preceding extras");
			check(spaced.getLineAscent(i)==plain.getLineAscent(i), "Spacing must not change ascent");
			int extra=i+1==count ? 0 : (int)roundSigned((plain.getLineDescent(i)-plain.getLineAscent(i))*(mult-1.0)+add);
			check(spaced.getLineDescent(i)==plain.getLineDescent(i)+extra, "Extra belongs below baseline");
			check(spaced.getLineForVertical(spaced.getLineTop(i))==i, "Hit testing must follow line tops");
			shift+=extra;
		}
		check(spaced.getHeight()==plain.getHeight()+shift, "No extra spacing after last line");
	}
	public static void run(Context context) {
		TextPaint paint=new TextPaint(); paint.setTextSize(20);
		compare("Ag\nAg\nAg",paint,12,1);
		compare("Ag\nAg\n",paint,5,1);
		compare("Ag",paint,50,2);
		compare("Ag\nAg\nAg",paint,-2,1);
		compare("Ag\nAg\nAg",paint,0,0.5f);
		SpannableString mixed=new SpannableString("Ag\nAg\nAg");
		mixed.setSpan(new AbsoluteSizeSpan(36),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		compare(mixed,paint,3,1.5f);
		TextView label=new TextView(context); label.setText("Ag\nAg\nAg"); label.setTextSize(20);
		int width=View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY);
		int height=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED);
		label.measure(width,height); int before=label.getMeasuredHeight();
		label.setLineSpacing(10,1); label.measure(width,height);
		check(Math.abs(label.getMeasuredHeight()-before-20)<=1,"Widget height follows line geometry");
		check(label.getLineSpacingExtra()==10 && label.getLineSpacingMultiplier()==1,"Spacing getters");
		EditText editor=new EditText(context); editor.setText("Ag");
		editor.measure(width,height); before=editor.getMeasuredHeight();
		editor.setLineSpacing(30,2); editor.measure(width,height);
		check(editor.getMeasuredHeight()==before,"Single line editor must not gain trailing spacing");
		android.widget.Button button = new android.widget.Button(context);
		button.setText("Ag\nAg\nAg"); button.setTextSize(20);
		button.measure(width,height); before=button.getMeasuredHeight();
		button.setLineSpacing(10,1); button.measure(width,height);
		check(Math.abs(button.getMeasuredHeight()-before-20)<=1,"Button uses the same inter-line geometry as TextView");
		check("Ag\nAg\nAg".contentEquals(button.getText()),"Button text survives line-box wrapping");
		System.out.println("LINE_SPACING_CONTRACT PASS");
	}
}
