package com.piweb.launcher;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;

/** First-run provisioning and explicit adoption of an existing installation. */
public class SetupActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private EditText name, web, policy;
    private Button use, check, install;
    private JSONObject found;
    private boolean busy;
    private String previousJob;
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String PREPARE = "mkdir -p ~/.termux && touch ~/.termux/termux.properties && "
            + "cp ~/.termux/termux.properties ~/.termux/termux.properties.piweb-$(date +%Y%m%d-%H%M%S).bak && "
            + "sed -i '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' ~/.termux/termux.properties && "
            + "printf '\\nallow-external-apps = true\\n' >> ~/.termux/termux.properties && "
            + "termux-reload-settings && pkg install -y curl python";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        DeploymentConfig.load(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10,12,16));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(24); box.setPadding(p,p,p,p);
        scroll.addView(box);
        text(box, "Pi Web · 环境安装", 24);
        text(box, "方案 B：APK 携带 Pi Web，Termux 提供 Node 和 Shell。首版支持官方 com.termux / ARM64。不会导入本机密钥、使用 root、开启自启动或覆盖已有安装。", 14);
        text(box, "① 安装并首次打开官方 Termux，等基础初始化完成。\n② 复制下方命令到 Termux 执行：只备份并修改 allow-external-apps，安装 curl/python；会联网。不要在未初始化的 Termux 上直接检查。\n③ 返回这里授权，再检查或安装。", 14);
        button(box, "打开 Termux", new Runnable() { public void run() {
            if (!TermuxRunner.wakeTermux(SetupActivity.this)) toast("请先从 Termux 官方 GitHub 或 F-Droid 安装 com.termux");
        }});
        button(box, "查看并复制初始化命令", new Runnable() { public void run() {
            new AlertDialog.Builder(SetupActivity.this).setTitle("在 Termux 手动执行")
                    .setMessage("此命令将备份 termux.properties，开启外部调用，并安装 curl/python。不会更换软件源或全量升级。\n\n" + PREPARE)
                    .setPositiveButton("复制", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Termux 初始化", PREPARE)); toast("已复制，请在 Termux 检查后执行");
                    }}).setNegativeButton("取消", null).show();
        }});
        button(box, "授权在 Termux 中执行命令", new Runnable() { public void run() {
            if (!TermuxRunner.isTermuxInstalled(SetupActivity.this)) { toast("请先安装 Termux"); return; }
            if (checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) toast("已授权，还需在 Termux 开启 allow-external-apps");
            else requestPermissions(new String[]{PERMISSION}, 1101);
        }});
        text(box, "安装目录（Termux 家目录下的单个目录名）", 13);
        name = input(box, "piweb", false);
        text(box, "Web 端口 / 策略服务端口（仅本机）", 13);
        web = input(box, "30141", true); policy = input(box, "30142", true);
        if (DeploymentConfig.ready()) {
            name.setText(DeploymentConfig.dir().substring(DeploymentConfig.HOME.length()+1));
            web.setText(DeploymentConfig.webUrl().substring(DeploymentConfig.webUrl().lastIndexOf(':')+1));
            policy.setText(DeploymentConfig.policyUrl().substring(DeploymentConfig.policyUrl().lastIndexOf(':')+1));
        }
        try {
            JSONObject m = new JSONObject(readAsset("provision/manifest.json"));
            long packed = m.getJSONObject("files").getJSONObject("piweb.tar.gz").getLong("size");
            long unpacked = m.getLong("unpacked_bytes");
            text(box, "内置 Pi Web " + m.getString("pi_web_version") + " · 资源包 " + packed/1048576 + " MiB · 解压约 " + unpacked/1048576
                    + " MiB\n至少预留 " + (packed+unpacked+268435456)/1048576 + " MiB 空间，另加 Termux 依赖占用。Node/其他缺失依赖仍需从官方源联网安装。", 13);
        } catch (Exception e) { text(box, "资源清单缺失：此 APK 不可用于自动安装", 13); }
        check = button(box, "检查目录 / 发现现有安装", new Runnable() { public void run() { startJob("probe"); }});
        install = button(box, "确认安装到新目录", new Runnable() { public void run() {
            new AlertDialog.Builder(SetupActivity.this).setTitle("确认安装")
                    .setMessage("将从 APK 传输并解压 Pi Web，必要时从当前 Termux 官方源安装 Node、git、bash、tar 等缺失依赖。只使用 Termux 应用权限。已有目录或同名配置不会覆盖。\n\n确认后出现安装进度通知；取消会在安全阶段生效，不中途强杀包管理器。")
                    .setPositiveButton("安装", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d,int w) { startJob("install"); }})
                    .setNegativeButton("取消", null).show();
        }});
        use = button(box, "接管发现的安装并打开", new Runnable() { public void run() { acceptFound(); }});
        use.setEnabled(false);
        button(box, "取消当前安装任务", new Runnable() { public void run() {
            ProvisionServer s = ProvisionService.server;
            if (s != null && !s.terminal()) {
                s.cancel();
                if ("waiting".equals(s.state().optString("phase"))) s.localState("cancelled", "已取消等待；如有 Termux 命令已启动，会在下一检查点退出");
                toast("已请求取消；依赖安装完成后才会停止，不强杀进程");
            }
        }});
        if (DeploymentConfig.ready()) button(box, "不改配置，返回现有 Pi Web", new Runnable() { public void run() { openMain(); }});
        status = text(box, "尚未检查。目录存在时请接管或换名字，不要删除原环境。", 14);
        if (getSharedPreferences("provision", MODE_PRIVATE).getBoolean("interrupted", false) && ProvisionService.server == null) {
            status.setText("上次安装可能被系统中断。请先检查目录和 Termux 日志；不会自动覆盖半安装目录。必要时换一个新目录名重试。");
        }
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                public WindowInsets onApplyWindowInsets(View v, WindowInsets i) {
                    android.graphics.Insets s = i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    v.setPadding(s.left,s.top,s.right,s.bottom); return i;
                }
            });
        } else scroll.setFitsSystemWindows(true);
    }
    private String readAsset(String path) throws Exception {
        try (InputStream in = getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1)out.write(b,0,n); return out.toString("UTF-8");
        }
    }
    private void startJob(String action) {
        if (busy) { toast("请等待当前任务结束"); return; }
        if (checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) { toast("请先完成 Termux 初始化并授权"); return; }
        String n = name.getText().toString().trim();
        int w,p;
        try {
            w=Integer.parseInt(web.getText().toString().trim()); p=Integer.parseInt(policy.getText().toString().trim());
            if (!n.matches("[A-Za-z][A-Za-z0-9_-]{0,39}") || w<1024 || p<1024 || w>65535 || p>65535 || w==p) throw new Exception();
        } catch (Exception e) { toast("目录名须字母开头；端口范围 1024–65535 且不能相同"); return; }
        found=null; use.setEnabled(false); busy=true; check.setEnabled(false); install.setEnabled(false);
        previousJob=ProvisionService.jobId;
        Intent i=new Intent(this,ProvisionService.class).putExtra("mode",action).putExtra("name",n).putExtra("web",w).putExtra("policy",p);
        try {
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            status.setText("任务已下发，等待实际执行结果；若无回调，请打开 Termux 检查外部调用设置。");
        } catch (Exception e) {busy=false;check.setEnabled(true);install.setEnabled(true);status.setText("系统阻止启动安装任务："+e.getClass().getSimpleName());}
    }
    private final Runnable poll = new Runnable() {
        public void run() {
            ProvisionServer s=ProvisionService.server;
            if(s!=null && (!busy || previousJob==null || !previousJob.equals(ProvisionService.jobId))) {
                JSONObject result=s.state();
                status.setText(result.optString("message") + (result.has("warnings") ? "\n"+result.optJSONArray("warnings").toString() : ""));
                String phase=result.optString("phase");
                if(s.terminal()) {
                    busy=false; check.setEnabled(true); install.setEnabled(true);
                    if(phase.equals("existing") || phase.equals("ready")) {found=result.optJSONObject("config");use.setEnabled(found!=null);}
                } else {busy=true;check.setEnabled(false);install.setEnabled(false);}
            }
            handler.postDelayed(this,700);
        }
    };
    private void acceptFound() {
        if(found==null)return;
        try {DeploymentConfig.save(this,found);openMain();}
        catch(Exception e){toast("部署参数校验失败，未保存："+e.getMessage());}
    }
    private void openMain() {startActivity(new Intent(this,MainActivity.class).putExtra("deployment_changed",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}
    @Override protected void onResume(){super.onResume();handler.post(poll);}
    @Override protected void onPause(){handler.removeCallbacks(poll);super.onPause();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout box,String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.rgb(220,226,234));t.setTextSize(size);t.setTextIsSelectable(true);t.setPadding(0,dp(8),0,dp(8));box.addView(t);return t;}
    private Button button(LinearLayout box,String title,final Runnable action){Button b=new Button(this);b.setText(title);box.addView(b);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});return b;}
    private EditText input(LinearLayout box,String value,boolean numeric){EditText e=new EditText(this);e.setTextColor(Color.WHITE);e.setSingleLine(true);if(numeric)e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);e.setText(value);box.addView(e);return e;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
