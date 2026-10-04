package com.demo.controlcenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class OnboardingActivity extends Activity {

    private float density;

    private LinearLayout contentContainer;
    private LinearLayout dotIndicator;
    private TextView btnNext;
    private LinearLayout bottomButtonRow;

    private CheckBox agreeCheckbox = null;
    private ScrollView agreementScroll = null;

    private int currentPage = 0;
    private boolean rendering = false;

    private static final String COLOR_BG        = "#F2F2F7";
    private static final String COLOR_TEXT      = "#1C1C1E";
    private static final String COLOR_DESC      = "#8E8E93";
    private static final String COLOR_AGREE_BG  = "#FFFFFF";
    private static final String COLOR_AGREE_TX  = "#3A3A3C";
    private static final String COLOR_BLUE      = "#007AFF";
    private static final String COLOR_DOT_OFF   = "#D1D1D6";
    private static final String COLOR_WHITE     = "#FFFFFF";
    private static final String COLOR_DISABLED  = "#C7C7CC";
    private static final String COLOR_RED       = "#FF3B30";

    private static final String COLOR_ICON_BG_0 = "#E3F0FF";
    private static final String COLOR_ICON_BG_1 = "#FFF3E0";
    private static final String COLOR_ICON_BG_2 = "#FFEBEE";
    private static final String COLOR_ICON_BG_3 = "#E8F8EC";

    private static final String SP_APP         = "app_config";
    private static final String K_FIRST_LAUNCH = "first_launch";

    private static class PageData {
        final String title;
        final String desc;
        final int    iconRes;
        final String iconBg;

        PageData(String title, String desc, int iconRes, String iconBg) {
            this.title   = title;
            this.desc    = desc;
            this.iconRes = iconRes;
            this.iconBg  = iconBg;
        }
    }

    private static final PageData[] PAGES = {
            new PageData(
                    "欢迎使用 驱动管理",
                    "这是一个本地驱动与内核模块管理工具，帮助你方便地刷入、启用、删除驱动。",
                    android.R.drawable.ic_menu_manage,
                    COLOR_ICON_BG_0),
            new PageData(
                    "什么是驱动",
                    "驱动（.ko 文件或 Magisk 模块）是让内核支持更多功能的关键组件，刷入后才能生效。",
                    android.R.drawable.ic_menu_info_details,
                    COLOR_ICON_BG_1),
            new PageData(
                    "使用前须知",
                    "本工具仅支持已 Root 的设备。刷入驱动存在风险，请务必确认你了解自己的设备型号与内核版本。",
                    android.R.drawable.ic_dialog_alert,
                    COLOR_ICON_BG_2),
            new PageData(
                    "用户协议与免责声明",
                    "继续使用即表示您已知晓并同意以下全部条款，请滑动阅读到底部后勾选确认。",
                    android.R.drawable.ic_menu_agenda,
                    COLOR_ICON_BG_3)
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        buildUI();
        renderPage(0);
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(COLOR_BG));
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(contentContainer);

        LinearLayout bottomArea = new LinearLayout(this);
        bottomArea.setOrientation(LinearLayout.VERTICAL);
        bottomArea.setGravity(Gravity.CENTER);

        dotIndicator = new LinearLayout(this);
        dotIndicator.setOrientation(LinearLayout.HORIZONTAL);
        dotIndicator.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dotParams.bottomMargin = dp(24);
        dotIndicator.setLayoutParams(dotParams);
        bottomArea.addView(dotIndicator);

        bottomButtonRow = new LinearLayout(this);
        bottomButtonRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomButtonRow.setGravity(Gravity.CENTER);
        bottomButtonRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        bottomArea.addView(bottomButtonRow);

        root.addView(bottomArea);
        setContentView(root);
    }

    private void renderPage(int index) {
        if (rendering) return;
        rendering = true;
        try {
            if (index < 0 || index >= PAGES.length) return;

            currentPage = index;
            contentContainer.removeAllViews();
            agreeCheckbox = null;
            agreementScroll = null;

            rebuildBottomButtons();

            PageData page = PAGES[index];

            ImageView icon = new ImageView(this);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(96), dp(96));
            ip.topMargin = dp(60);
            ip.bottomMargin = dp(40);
            ip.gravity = Gravity.CENTER_HORIZONTAL;
            icon.setLayoutParams(ip);
            icon.setBackground(createRoundRect(page.iconBg, dp(28)));
            icon.setPadding(dp(24), dp(24), dp(24), dp(24));
            icon.setImageResource(page.iconRes);
            contentContainer.addView(icon);

            TextView title = new TextView(this);
            title.setText(page.title);
            title.setTextSize(24);
            title.setTextColor(Color.parseColor(COLOR_TEXT));
            title.setTypeface(null, Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.bottomMargin = dp(16);
            title.setLayoutParams(tp);
            contentContainer.addView(title);

            TextView desc = new TextView(this);
            desc.setText(page.desc);
            desc.setTextSize(15);
            desc.setTextColor(Color.parseColor(COLOR_DESC));
            desc.setGravity(Gravity.CENTER);
            desc.setLineSpacing(dp(6), 1f);
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dp2.leftMargin  = dp(16);
            dp2.rightMargin = dp(16);
            desc.setLayoutParams(dp2);
            contentContainer.addView(desc);

            if (index == PAGES.length - 1) {
                addAgreementBlock();
            }

            updateDots();
            updateButtonState();
        } finally {
            rendering = false;
        }
    }

    private void addAgreementBlock() {
        agreementScroll = new ScrollView(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = dp(16);
        agreementScroll.setLayoutParams(sp);
        agreementScroll.setMinimumHeight(dp(120));

        TextView agreementText = new TextView(this);
        agreementText.setText(getAgreementContent());
        agreementText.setTextSize(13);
        agreementText.setTextColor(Color.parseColor(COLOR_AGREE_TX));
        agreementText.setLineSpacing(dp(4), 1f);
        agreementText.setPadding(dp(16), dp(16), dp(16), dp(16));
        agreementText.setBackground(createRoundRect(COLOR_AGREE_BG, dp(12)));

        agreementScroll.addView(agreementText);
        contentContainer.addView(agreementScroll);

        agreeCheckbox = new CheckBox(this);
        agreeCheckbox.setText("请先滑动阅读到底部");
        agreeCheckbox.setTextSize(14);
        agreeCheckbox.setTextColor(Color.parseColor(COLOR_DISABLED));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(12);
        agreeCheckbox.setLayoutParams(cp);
        agreeCheckbox.setEnabled(false);
        agreeCheckbox.setChecked(false);
        agreeCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                updateButtonState());
        contentContainer.addView(agreeCheckbox);

        agreementScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (agreementScroll == null) return;
            View child = agreementScroll.getChildAt(0);
            if (child == null) return;
            int scrollY = agreementScroll.getScrollY();
            int viewH   = agreementScroll.getHeight();
            int contentH = child.getMeasuredHeight();
            if (viewH <= 0) return;
            if (scrollY + viewH >= contentH - dp(24)) {
                unlockAgreement();
            }
        });

        agreementScroll.post(() -> {
            if (agreementScroll == null) return;
            View child = agreementScroll.getChildAt(0);
            if (child == null) return;
            if (child.getMeasuredHeight() <= agreementScroll.getHeight()) {
                unlockAgreement();
            }
        });
    }

    private void unlockAgreement() {
        if (agreeCheckbox == null) return;
        if (agreeCheckbox.isEnabled()) return;
        agreeCheckbox.setEnabled(true);
        agreeCheckbox.setText("我已阅读并同意以上全部条款");
        agreeCheckbox.setTextColor(Color.parseColor(COLOR_TEXT));
    }

    private void rebuildBottomButtons() {
        bottomButtonRow.removeAllViews();

        boolean isLast = (currentPage == PAGES.length - 1);

        btnNext = new TextView(this);
        btnNext.setText("下一步");
        btnNext.setTextSize(16);
        btnNext.setTextColor(Color.parseColor(COLOR_WHITE));
        btnNext.setTypeface(null, Typeface.BOLD);
        btnNext.setGravity(Gravity.CENTER);
        btnNext.setBackground(createRoundRect(COLOR_BLUE, dp(28)));
        btnNext.setOnClickListener(v -> goNextPage());
        setPressFeedback(btnNext);

        if (isLast) {
            bottomButtonRow.addView(btnNext, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            TextView btnReject = new TextView(this);
            btnReject.setText("拒绝并退出");
            btnReject.setTextSize(16);
            btnReject.setTextColor(Color.parseColor(COLOR_RED));
            btnReject.setTypeface(null, Typeface.BOLD);
            btnReject.setGravity(Gravity.CENTER);
            btnReject.setBackground(createRoundRect(COLOR_AGREE_BG, dp(28)));
            btnReject.setOnClickListener(v -> confirmReject());
            setPressFeedback(btnReject);

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            rp.setMarginStart(dp(12));
            bottomButtonRow.addView(btnReject, rp);
        } else {
            bottomButtonRow.addView(btnNext, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void goNextPage() {
        if (rendering) return;

        if (currentPage < PAGES.length - 1) {
            renderPage(currentPage + 1);
            return;
        }

        if (agreeCheckbox == null || !agreeCheckbox.isEnabled()) {
            Toast.makeText(this, "请先滑动阅读到底部", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!agreeCheckbox.isChecked()) {
            Toast.makeText(this, "请先勾选我已阅读", Toast.LENGTH_SHORT).show();
            return;
        }

        saveFirstLaunchFlag();
        openMainActivity();
        finish();
    }

    private void confirmReject() {
        new AlertDialog.Builder(this)
                .setTitle("确认拒绝")
                .setMessage("确定要拒绝并退出吗？\n\n拒绝后将无法使用本工具。")
                .setCancelable(true)
                .setPositiveButton("拒绝并退出", (d, w) -> {
                    finishAffinity();
                    System.exit(0);
                })
                .setNegativeButton("再想想", null)
                .show();
    }

    private void openMainActivity() {
        try {
            Class<?> clazz = Class.forName("com.demo.controlcenter.MainActivity");
            startActivity(new Intent(OnboardingActivity.this, clazz));
        } catch (Exception e) {
            Toast.makeText(this,
                    "启动主界面失败: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void updateDots() {
        dotIndicator.removeAllViews();
        for (int i = 0; i < PAGES.length; i++) {
            View dot = new View(this);
            int size = (i == currentPage) ? dp(24) : dp(8);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, dp(8));
            p.setMarginStart(dp(4));
            p.setMarginEnd(dp(4));
            dot.setLayoutParams(p);

            String color = (i == currentPage) ? COLOR_BLUE : COLOR_DOT_OFF;
            dot.setBackground(createRoundRect(color, dp(4)));
            dotIndicator.addView(dot);
        }
    }

    private void updateButtonState() {
        boolean isLast = (currentPage == PAGES.length - 1);
        if (!isLast) return;
        if (btnNext == null) return;

        boolean canCheck = (agreeCheckbox != null && agreeCheckbox.isEnabled());
        boolean checked  = (agreeCheckbox != null && agreeCheckbox.isChecked());

        if (!canCheck) {
            btnNext.setText("请先滑动到底部");
            btnNext.setBackground(createRoundRect(COLOR_DISABLED, dp(28)));
            btnNext.setEnabled(true);
            btnNext.setAlpha(0.9f);
            return;
        }
        if (!checked) {
            btnNext.setText("请先勾选我已阅读");
            btnNext.setBackground(createRoundRect(COLOR_DISABLED, dp(28)));
            btnNext.setEnabled(true);
            btnNext.setAlpha(0.9f);
            return;
        }
        btnNext.setText("同意并进入");
        btnNext.setBackground(createRoundRect(COLOR_BLUE, dp(28)));
        btnNext.setEnabled(true);
        btnNext.setAlpha(1f);
    }

    private void saveFirstLaunchFlag() {
        SharedPreferences sp = getSharedPreferences(SP_APP, MODE_PRIVATE);
        sp.edit().putBoolean(K_FIRST_LAUNCH, false).apply();
    }

    private String getAgreementContent() {
        return new StringBuilder()
                .append("用户协议与免责声明\n\n")

                .append("一、本工具不做的事\n")
                .append("本工具不对您选择的刷入文件进行任何形式的检查、认证或核对，包括但不限于：\n")
                .append("1. 不检查文件格式（.zip / .sh / .ko / 其他）\n")
                .append("2. 不检查脚本内容是否含有危险命令\n")
                .append("3. 不检查驱动与当前内核是否匹配\n")
                .append("4. 不校验文件来源与签名\n\n")
                .append("您选择什么文件，本工具就按什么文件刷入。\n\n")

                .append("二、本工具做的事\n")
                .append("本工具仅提供以下过程性保护：\n")
                .append("1. 刷入前的 boot 与模块目录备份\n")
                .append("2. 刷入过程的超时监控与强制中断\n")
                .append("3. 刷入失败后的回滚与 SELinux 上下文修复\n")
                .append("4. 刷入后的模块状态校验\n\n")
                .append("这些保护是「安全绳」，不是「护栏」。它不能阻止您刷错，"
                        + "只能在您刷错后尽量把设备救回来。\n\n")

                .append("三、责任划分\n")
                .append("1. 刷入什么文件，由您自行判断。\n")
                .append("2. 产生什么后果，由您自行承担。\n")
                .append("3. 因刷入不当导致的无法开机、数据丢失、设备变砖、保修失效等问题，"
                        + "本工具及作者不承担任何责任。\n\n")

                .append("四、继续使用即表示\n")
                .append("您已阅读、理解并同意以上全部条款。\n\n")

                .append("五、用户确认\n")
                .append("您确认已年满 18 周岁，并具备对设备进行 Root 及刷入操作的基本认知。\n\n")
                .append("—— 以上条款最终解释权归本工具作者所有 ——\n\n")
                .append("（本文档到此结束）")
                .toString();
    }

    private void setPressFeedback(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.97f).scaleY(0.97f)
                            .setDuration(100).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(350)
                            .setInterpolator(new OvershootInterpolator(2.5f))
                            .start();
                    break;
            }
            return false;
        });
    }

    private GradientDrawable createRoundRect(String colorHex, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(Color.parseColor(colorHex));
        d.setCornerRadius(radiusPx);
        return d;
    }

    private int dp(float dpValue) {
        return (int) (dpValue * density + 0.5f);
    }
}