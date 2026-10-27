package com.cyber.deep;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private EditText etInput;
    private TextView tvStatus;
    private GeckoView geckoView;
    private GeckoSession session;
    private static GeckoRuntime runtime;

    private boolean isPageReady = false;
    private boolean isRunning = false;
    private boolean tokenInjected = false;
    private boolean scriptInjected = false;
    
    // 你提供的真实 Token
    private final String MY_TOKEN = "WlS6knRr+HvB+h7ioKh4LgxmyfJXJfQ3jJaYfuYzQQ9oOthcRovka6o83lKFspw5";

    private MsgAdapter adapter;
    private final List<Msg> msgList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.recyclerView);
        etInput = findViewById(R.id.etInput);
        ImageButton btnSend = findViewById(R.id.btnSend);
        geckoView = findViewById(R.id.geckoView);
        tvStatus = findViewById(R.id.tvStatus);

        adapter = new MsgAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        initGeckoView();

        btnSend.setOnClickListener(v -> {
            String prompt = etInput.getText().toString().trim();
            if (TextUtils.isEmpty(prompt) || isRunning || !isPageReady) return;
            etInput.setText("");
            addMsg("User", prompt);
            isRunning = true;
            tvStatus.setText("生成中...");
            
            // 触发网页内部自动填字发送
            String js = "javascript:window.cyberSend(" + JSONObject.quote(prompt) + ");";
            session.loadUri(js);
        });
    }

    private void initGeckoView() {
        if (runtime == null) runtime = GeckoRuntime.create(this);
        session = new GeckoSession();

        // 监听网页标题变化，用于状态同步和接收回复
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(@NonNull GeckoSession s, String title) {
                if (title == null) return;
                
                if (title.contains("DeepSeek") || title.contains("deepseek")) {
                    if (!tokenInjected) {
                        tokenInjected = true;
                        tvStatus.setText("写入凭证并刷新...");
                        session.loadUri("javascript:localStorage.setItem('userToken', JSON.stringify({value:'" + MY_TOKEN + "', __version:'0'}));");
                        mainHandler.postDelayed(() -> session.reload(), 300);
                    } else if (!scriptInjected) {
                        scriptInjected = true;
                        injectScript();
                    }
                } else if (title.startsWith("REPLY:")) {
                    isRunning = false;
                    tvStatus.setText("就绪");
                    updateLastAiMsg(title.substring(6));
                }
            }
        });

        session.open(runtime);
        geckoView.setSession(session);
        session.loadUri("https://chat.deepseek.com");
        
        // 8秒兜底注入，防止网页标题加载慢
        mainHandler.postDelayed(() -> {
            if (!tokenInjected) {
                tokenInjected = true;
                session.loadUri("javascript:localStorage.setItem('userToken', JSON.stringify({value:'" + MY_TOKEN + "', __version:'0'}));");
                mainHandler.postDelayed(() -> session.reload(), 300);
            }
        }, 8000);
    }

    private void injectScript() {
        if (isPageReady) return;
        isPageReady = true;

        String js =
            "(function() {" +
            "  if (window.cyberHooked) { document.title = 'READY'; return; }" +
            "  window.cyberHooked = true;" +
            "  window.cyberSend = function(text) {" +
            "    let ta = document.querySelector('textarea');" +
            "    if (!ta) return;" +
            "    let setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;" +
            "    setter.call(ta, text);" +
            "    ta.dispatchEvent(new Event('input', { bubbles: true }));" +
            "    setTimeout(function() {" +
            "      ta.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));" +
            "    }, 300);" +
            "  };" +
            "  let lastText = '';" +
            "  setInterval(function() {" +
            "    let blocks = document.querySelectorAll('.ds-markdown');" +
            "    if (blocks.length > 0) {" +
            "      let latest = blocks[blocks.length - 1].innerText;" +
            "      if (latest !== lastText) { " +
            "        lastText = latest; " +
            "        document.title = 'REPLY:' + latest;" +
            "      }" +
            "    }" +
            "  }, 500);" +
            "})();";
        
        session.loadUri("javascript:" + js);
        
        // 稍微延迟一下，让用户看到就绪状态
        mainHandler.postDelayed(() -> {
            tvStatus.setText("隐藏内核就绪");
        }, 500);
    }

    private void updateLastAiMsg(String fullText) {
        if (msgList.isEmpty() || !"AI".equals(msgList.get(msgList.size() - 1).role)) {
            addMsg("AI", fullText);
        } else {
            Msg last = msgList.get(msgList.size() - 1);
            last.content = fullText;
            adapter.notifyItemChanged(msgList.size() - 1);
            recyclerView.smoothScrollToPosition(msgList.size() - 1);
        }
    }

    private void addMsg(String role, String content) {
        msgList.add(new Msg(role, content));
        adapter.notifyItemInserted(msgList.size() - 1);
        recyclerView.smoothScrollToPosition(msgList.size() - 1);
    }

    class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            Msg msg = msgList.get(position);
            holder.tvContent.setText(msg.content);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if ("User".equals(msg.role)) {
                params.gravity = android.view.Gravity.END;
                holder.tvContent.setBackgroundResource(R.drawable.bg_user_bubble);
                holder.tvContent.setTextColor(0xFFFFFFFF);
            } else {
                params.gravity = android.view.Gravity.START;
                holder.tvContent.setBackgroundResource(R.drawable.bg_ai_bubble);
                holder.tvContent.setTextColor(0xFF00E5FF);
            }
            holder.tvContent.setLayoutParams(params);
        }
        @Override public int getItemCount() { return msgList.size(); }
        class VH extends RecyclerView.ViewHolder { TextView tvContent; VH(View v) { super(v); tvContent = v.findViewById(R.id.tvContent); } }
    }
    static class Msg { String role, content; Msg(String role, String content) { this.role = role; this.content = content; } }
}
