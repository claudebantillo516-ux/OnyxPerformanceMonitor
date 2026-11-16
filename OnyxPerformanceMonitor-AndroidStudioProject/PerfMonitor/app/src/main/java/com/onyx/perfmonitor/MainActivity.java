package com.onyx.perfmonitor;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Sample> samples = new ArrayList<>();
    private final StringBuilder csv = new StringBuilder("elapsed_s,package,fps,current_mA,voltage_V,power_W,battery_pct,cpu0_pct,cpu1_pct,cpu2_pct,cpu3_pct,cpu4_pct,cpu5_pct,cpu6_pct,cpu7_pct\n");
    private TextView status, fps, power, current, voltage, battery, packageName, averages, summary;
    private final TextView[] cpuViews = new TextView[8];
    private CheckBox showAverages;
    private Button startButton, stopButton, exportButton;
    private boolean running = false, rootAvailable = false;
    private long startMs;
    private long[] previousTotal = new long[8], previousIdle = new long[8];
    private boolean haveCpuBaseline = false;
    private String previousCpuRaw = "";
    private String lastPackage = "Unknown";

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (!running) return;
            worker.execute(() -> {
                Sample s = readSample();
                runOnUiThread(() -> {
                    if (!running || s == null) return;
                    samples.add(s);
                    render(s);
                    if (showAverages.isChecked()) renderAverages();
                    else averages.setVisibility(View.GONE);
                    handler.postDelayed(sampler, 1000);
                });
            });
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        worker.execute(() -> {
            String out = rootCommand("id");
            rootAvailable = out != null && out.contains("uid=0");
            runOnUiThread(() -> {
                status.setText(rootAvailable ? "ROOT CONNECTED · KernelSU Next" : "ROOT NOT AVAILABLE · Grant root when prompted");
                status.setTextColor(rootAvailable ? Color.rgb(101,214,165) : Color.rgb(255,190,90));
            });
        });
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(18),dp(20),dp(18),dp(24));
        page.setBackgroundColor(Color.rgb(16,21,29)); scroll.addView(page); setContentView(scroll);
        TextView title = text("ONYX PERFORMANCE MONITOR",22,true,Color.WHITE); page.addView(title);
        status = text("Checking root access…",13,false,Color.LTGRAY); add(page,status,0,12);
        packageName = text("Foreground app: —",13,false,Color.LTGRAY); add(page,packageName,0,18);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this); startButton.setText("START"); startButton.setOnClickListener(v -> startSession());
        stopButton = new Button(this); stopButton.setText("STOP"); stopButton.setEnabled(false); stopButton.setOnClickListener(v -> stopSession());
        actions.addView(startButton,new LinearLayout.LayoutParams(0,dp(52),1)); actions.addView(stopButton,new LinearLayout.LayoutParams(0,dp(52),1)); page.addView(actions);
        add(page, section("LIVE READINGS"),0,20);
        fps = metric(page,"FPS","—"); power = metric(page,"BATTERY POWER","—"); current = metric(page,"BATTERY CURRENT","—"); voltage = metric(page,"BATTERY VOLTAGE","—"); battery = metric(page,"BATTERY LEVEL","—");
        add(page, section("INDIVIDUAL CPU LOAD · SYSTEM-WIDE"),0,18);
        for(int i=0;i<8;i++) cpuViews[i]=metric(page,"CPU "+i,"—");
        showAverages = new CheckBox(this); showAverages.setText("Show session averages"); showAverages.setTextColor(Color.WHITE); showAverages.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(101,214,165))); showAverages.setOnCheckedChangeListener((b,checked)->{ if(checked && !samples.isEmpty()) renderAverages(); else averages.setVisibility(View.GONE); }); add(page,showAverages,0,16);
        averages = text("",14,false,Color.rgb(190,205,220)); averages.setVisibility(View.GONE); add(page,averages,0,8);
        add(page, section("SESSION SUMMARY"),0,18);
        summary = text("No session yet.",14,false,Color.LTGRAY); add(page,summary,0,10);
        exportButton = new Button(this); exportButton.setText("EXPORT CSV"); exportButton.setEnabled(false); exportButton.setOnClickListener(v -> exportCsv()); page.addView(exportButton);
        TextView note=text("FPS is estimated from SurfaceFlinger frame-present timestamps for the foreground app. CPU loads are system-wide, not game-process-only. Battery power is battery-side power; unplug the phone for discharge measurements. Root access is required.",12,false,Color.GRAY); add(page,note,0,16);
    }

    private TextView metric(LinearLayout parent,String label,String value){
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12),dp(9),dp(12),dp(9)); card.setBackgroundColor(Color.rgb(28,36,47));
        TextView l=text(label,11,true,Color.rgb(155,174,194)); TextView v=text(value,21,true,Color.WHITE); card.addView(l); card.addView(v); add(parent,card,0,7); card.setTag(v); return v;
    }
    private TextView section(String s){return text(s,13,true,Color.rgb(101,214,165));}
    private TextView text(String s,int size,boolean bold,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void add(LinearLayout p,View v,int width,int bottom){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(width==0?-1:width,-2);lp.bottomMargin=dp(bottom);p.addView(v,lp);}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}

    private void startSession(){
        if(running)return;
        samples.clear(); csv.setLength(0); csv.append("elapsed_s,package,fps,current_mA,voltage_V,power_W,battery_pct,cpu0_pct,cpu1_pct,cpu2_pct,cpu3_pct,cpu4_pct,cpu5_pct,cpu6_pct,cpu7_pct\n");
        haveCpuBaseline=false; previousCpuRaw=""; startMs=System.currentTimeMillis(); running=true;
        startButton.setEnabled(false); stopButton.setEnabled(true); exportButton.setEnabled(false); summary.setText("Session running…");
        status.setText("MEASURING · Tap STOP when finished"); status.setTextColor(Color.rgb(101,214,165));
        sampler.run();
    }
    private void stopSession(){
        if(!running)return; running=false; handler.removeCallbacks(sampler); startButton.setEnabled(true); stopButton.setEnabled(false); exportButton.setEnabled(!samples.isEmpty());
        status.setText("SESSION STOPPED · "+samples.size()+" samples"); status.setTextColor(Color.LTGRAY); showSummary();
        if(showAverages.isChecked())renderAverages();
    }

    private Sample readSample(){
        String script = "APP=$(dumpsys window 2>/dev/null | grep -m 1 'mCurrentFocus=Window' | sed 's/.* u[0-9]* //; s#/.*##'); " +
          "[ -n \"$APP\" ] || APP=Unknown; " +
          "LAYER=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"$APP\" | grep 'SurfaceView' | grep 'BLAST' | head -n 1); " +
          "[ -n \"$LAYER\" ] || LAYER=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"$APP\" | grep 'SurfaceView' | head -n 1); " +
          "echo __APP__; echo \"$APP\"; echo __BAT__; cat /sys/class/power_supply/battery/current_now 2>/dev/null; cat /sys/class/power_supply/battery/voltage_now 2>/dev/null; cat /sys/class/power_supply/battery/capacity 2>/dev/null; " +
          "echo __CPU__; cat /proc/stat; echo __FPS__; if [ -n \"$LAYER\" ]; then dumpsys SurfaceFlinger --latency \"$LAYER\" 2>/dev/null; fi";
        String out=rootCommand(script); if(out==null)return null;
        try{
            String app=between(out,"__APP__\n","__BAT__").trim(); String bat=between(out,"__BAT__\n","__CPU__").trim(); String cpu=between(out,"__CPU__\n","__FPS__"); String lat=out.substring(out.indexOf("__FPS__")+7);
            String[] b=bat.split("\\s+"); if(b.length<3)return null;
            long rawCurrent=Long.parseLong(b[0].trim()), rawVoltage=Long.parseLong(b[1].trim()); int pct=Integer.parseInt(b[2].trim());
            Sample s=new Sample(); s.timeMs=System.currentTimeMillis()-startMs; s.app=app.isEmpty()?"Unknown":app; lastPackage=s.app;
            s.currentMa=-rawCurrent/1000.0; s.voltage=rawVoltage/1000000.0; s.power=-(rawCurrent*(double)rawVoltage)/1.0e12; s.battery=pct; s.fps=parseFps(lat);
            s.cpu=parseCpu(cpu); appendCsv(s); return s;
        }catch(Exception e){return null;}
    }
    private String between(String s,String a,String b){int x=s.indexOf(a);if(x<0)return "";x+=a.length();int y=s.indexOf(b,x);return y<0?"":s.substring(x,y);}
    private String rootCommand(String command){
        Process p=null; try{p=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();InputStream in=p.getInputStream();ByteArrayOutputStreamEx buf=new ByteArrayOutputStreamEx();byte[] data=new byte[8192];int n;while((n=in.read(data))!=-1)buf.write(data,0,n);if(!p.waitFor(8,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();return null;}return buf.toString(StandardCharsets.UTF_8.name());}catch(Exception e){if(p!=null)p.destroyForcibly();return null;}}
    private double parseFps(String latency){String[] lines=latency.split("\\n");ArrayList<Long> ts=new ArrayList<>();for(int i=1;i<lines.length;i++){String[] f=lines[i].trim().split("\\s+");if(f.length>=2){try{long t=Long.parseLong(f[1]);if(t>0&&(ts.isEmpty()||ts.get(ts.size()-1)!=t))ts.add(t);}catch(Exception ignored){}}}if(ts.size()<2)return -1;long first=ts.get(0),last=ts.get(ts.size()-1);if(last<=first)return -1;return (ts.size()-1)*1.0e9/(last-first);}
    private double[] parseCpu(String raw){double[] loads=new double[8];String[] lines=raw.split("\\n");Map<Integer,long[]> now=new LinkedHashMap<>();for(String line:lines){String[] f=line.trim().split("\\s+");if(f.length<5||!f[0].matches("cpu[0-7]"))continue;try{int id=Integer.parseInt(f[0].substring(3));long total=0;for(int j=1;j<f.length;j++)total+=Long.parseLong(f[j]);long idle=Long.parseLong(f[4])+(f.length>5?Long.parseLong(f[5]):0);now.put(id,new long[]{total,idle});}catch(Exception ignored){}}
        if(haveCpuBaseline){for(int i=0;i<8;i++){long[] n=now.get(i);if(n!=null){long dt=n[0]-previousTotal[i],di=n[1]-previousIdle[i];loads[i]=dt>0?Math.max(0,Math.min(100,100.0*(dt-di)/dt)):-1;}else loads[i]=-1;}}else{for(int i=0;i<8;i++)loads[i]=-1;}
        for(int i=0;i<8;i++){long[] n=now.get(i);if(n!=null){previousTotal[i]=n[0];previousIdle[i]=n[1];}}haveCpuBaseline=true;return loads;}
    private void appendCsv(Sample s){StringBuilder row=new StringBuilder(String.format(Locale.US,"%.1f,%s,%.2f,%.2f,%.3f,%.3f,%d",s.timeMs/1000.0,s.app.replace(",","_"),s.fps,s.currentMa,s.voltage,s.power,s.battery));for(double x:s.cpu)row.append(',').append(x<0?"":String.format(Locale.US,"%.2f",x));csv.append(row).append('\n');}
    private void render(Sample s){packageName.setText("Foreground app: "+s.app);fps.setText(s.fps<0?"Unavailable":String.format(Locale.US,"%.1f FPS",s.fps));power.setText(String.format(Locale.US,"%.2f W",s.power)+ (s.currentMa>=0?" · discharge":" · charging/other"));current.setText(String.format(Locale.US,"%.0f mA",s.currentMa));voltage.setText(String.format(Locale.US,"%.3f V",s.voltage));battery.setText(s.battery+"%");for(int i=0;i<8;i++)cpuViews[i].setText(s.cpu[i]<0?"Waiting for sample":String.format(Locale.US,"%.1f%%",s.cpu[i]));}
    private void renderAverages(){if(samples.isEmpty())return;double f=0,p=0,c=0;int nf=0;double[] cp=new double[8];int[] nc=new int[8];for(Sample s:samples){if(s.fps>=0){f+=s.fps;nf++;}p+=s.power;c+=s.currentMa;for(int i=0;i<8;i++)if(s.cpu[i]>=0){cp[i]+=s.cpu[i];nc[i]++;}}StringBuilder x=new StringBuilder("SESSION AVERAGES\n");x.append("FPS: ").append(nf>0?String.format(Locale.US,"%.1f",f/nf):"Unavailable").append("\nPower: ").append(String.format(Locale.US,"%.3f W",p/samples.size())).append("\nCurrent: ").append(String.format(Locale.US,"%.1f mA",c/samples.size()));for(int i=0;i<8;i++)x.append("\nCPU").append(i).append(": ").append(nc[i]>0?String.format(Locale.US,"%.1f%%",cp[i]/nc[i]):"N/A");averages.setText(x.toString());averages.setVisibility(View.VISIBLE);}
    private void showSummary(){if(samples.isEmpty()){summary.setText("No valid samples collected.");return;}int start=samples.get(0).battery,end=samples.get(samples.size()-1).battery;double min=Double.MAX_VALUE,max=-1;int n=0;for(Sample s:samples)if(s.fps>=0){min=Math.min(min,s.fps);max=Math.max(max,s.fps);n++;}summary.setText("Duration: "+String.format(Locale.US,"%.1f min",(System.currentTimeMillis()-startMs)/60000.0)+"\nSamples: "+samples.size()+"\nFPS min/max: "+(n>0?String.format(Locale.US,"%.1f / %.1f",min,max):"Unavailable")+"\nBattery: "+start+"% → "+end+"% ("+(start-end)+"% change)");}
    private void exportCsv(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");i.putExtra(Intent.EXTRA_TITLE,"performance-session.csv");startActivityForResult(i,42);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==42&&result==RESULT_OK&&data!=null){try(OutputStream o=getContentResolver().openOutputStream(data.getData())){o.write(csv.toString().getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"CSV exported",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Export failed",Toast.LENGTH_LONG).show();}}}
    @Override protected void onDestroy(){running=false;handler.removeCallbacks(sampler);worker.shutdownNow();super.onDestroy();}
    private static class Sample{long timeMs;String app;double fps,currentMa,voltage,power;int battery;double[] cpu=new double[8];}
    private static class ByteArrayOutputStreamEx extends java.io.ByteArrayOutputStream {String toString(String charset)throws java.io.UnsupportedEncodingException{return super.toString(charset);}}
}
