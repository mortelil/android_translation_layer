/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.fragmentlife;
import android.app.*;
import android.os.*;
import java.util.*;
public class Probe extends Activity {
    boolean tested;
    public static class Fixture extends Fragment {
        final List<String> events=new ArrayList<>();
        public void onAttach(android.content.Context context){super.onAttach(context);events.add("attach");}
        public void onDetach(){events.add("detach");super.onDetach();}
        public void onCreate(Bundle state){super.onCreate(state);events.add("create");}
        public void onActivityCreated(Bundle state){super.onActivityCreated(state);events.add("activity");}
        public void onStart(){super.onStart();events.add("start");}
        public void onResume(){super.onResume();events.add("resume");}
        public void onPause(){events.add("pause");super.onPause();}
        public void onStop(){events.add("stop");super.onStop();}
        public void onDestroy(){events.add("destroy");super.onDestroy();}
    }
    void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    void expect(Fixture f,String... expected){check(f.events.equals(Arrays.asList(expected)),"events "+f.events+" expected "+Arrays.toString(expected));}
    public void onCreate(Bundle b){super.onCreate(b);setContentView(new android.widget.TextView(this));}
    public void onResume(){super.onResume();if(tested)return;tested=true;new Handler().post(()->{
        try{
            Fixture f=new Fixture();FragmentManager manager=getFragmentManager();
            manager.beginTransaction().add(f,"late").commit();manager.executePendingTransactions();
            expect(f,"attach","create","activity","start","resume");check(f.getActivity()==this,"attached host");
            manager.executePendingTransactions();expect(f,"attach","create","activity","start","resume");
            manager.beginTransaction().remove(f).commit();manager.executePendingTransactions();
            expect(f,"attach","create","activity","start","resume","pause","stop","destroy","detach");
            check(f.getActivity()==null,"removed host cleared");check(manager.findFragmentByTag("late")==null,"removed lookup");
            f.events.clear();manager.beginTransaction().add(f,"again").commit();manager.executePendingTransactions();
            expect(f,"attach","create","activity","start","resume");
            final Throwable[] failure={null};
            Fixture worker=new Fixture();
            Thread thread=new Thread(()->{try{manager.beginTransaction().add(worker,"worker").commit();}catch(Throwable t){failure[0]=t;}});
            thread.start();thread.join();check(failure[0]==null,"background enqueue "+failure[0]);
            manager.executePendingTransactions();expect(worker,"attach","create","activity","start","resume");
            try(java.io.FileWriter out=new java.io.FileWriter(new java.io.File(getFilesDir(),"results.json"))){out.write("{\"status\":\"PASS\"}");}
            System.out.println("FRAGMENT_LIFECYCLE PASS");
        }catch(Throwable t){t.printStackTrace();System.out.println("FRAGMENT_LIFECYCLE FAIL "+t);}
    });}
}
