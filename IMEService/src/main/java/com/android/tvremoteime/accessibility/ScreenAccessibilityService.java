package com.android.tvremoteime.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.tvremoteime.IMEService;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制端"元素列表"功能：读取当前屏幕上可点击的元素（文字/图标标签+位置），
 * 让用户直接从列表里点名字来操作电视，而不是像触控板那样盲划坐标。
 *
 * 跟电源键/触控板依赖的ADB shell input完全是两条独立路径：这里用的是安卓标准
 * 的无障碍API——AccessibilityNodeInfo.performAction(ACTION_CLICK)直接让目标
 * 控件执行它自己的点击逻辑，不区分这个控件平时是靠触摸响应还是靠遥控器焦点+
 * 确定键响应，所以在标准Android TV（D-pad焦点导航）系统上也能生效，不像触控板
 * 那样只对支持触屏的定制系统有用。也正因为不依赖ADB，不需要开发者选项/USB调试，
 * 只需要用户在系统设置-无障碍里手动开启这个服务一次。
 */
public class ScreenAccessibilityService extends AccessibilityService {
    private static ScreenAccessibilityService instance;

    //上一次/screenElements请求时抓到、还没被点击或被下一次刷新替换掉的节点，
    //配合数组下标给/clickElement用。AccessibilityNodeInfo用完必须recycle()，
    //否则在很多机型上会很快耗尽节点池导致后续查询失败。
    private final List<AccessibilityNodeInfo> cachedNodes = new ArrayList<>();

    public static class ElementInfo {
        public final int id;
        public final String label;
        public final int left, top, right, bottom;
        public ElementInfo(int id, String label, Rect bounds){
            this.id = id;
            this.label = label;
            this.left = bounds.left;
            this.top = bounds.top;
            this.right = bounds.right;
            this.bottom = bounds.bottom;
        }
    }

    public static ScreenAccessibilityService getInstance(){
        return instance;
    }

    public static boolean isServiceEnabled(){
        return instance != null;
    }

    @Override
    protected void onServiceConnected(){
        super.onServiceConnected();
        instance = this;
        Log.i(IMEService.TAG, "屏幕元素识别服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event){
        //不需要实时监听屏幕变化，控制端点"刷新"时主动拉取一次当前屏幕内容即可，
        //这里留空避免频繁事件回调带来不必要的开销。
    }

    @Override
    public void onInterrupt(){
    }

    @Override
    public boolean onUnbind(Intent intent){
        clearCachedNodes();
        instance = null;
        return super.onUnbind(intent);
    }

    private synchronized void clearCachedNodes(){
        for(AccessibilityNodeInfo node : cachedNodes){
            try {
                node.recycle();
            } catch (Exception ignored) {
            }
        }
        cachedNodes.clear();
    }

    /**
     * 抓取当前屏幕上可以点的元素，按遍历顺序编号，供控制端展示成列表。
     */
    public synchronized List<ElementInfo> queryClickableElements(){
        clearCachedNodes();
        List<ElementInfo> result = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if(root == null) return result;
        try {
            collectClickableNodes(root, result, 400, null);
        } finally {
            root.recycle();
        }
        return result;
    }

    private void collectClickableNodes(AccessibilityNodeInfo node, List<ElementInfo> result, int maxCount, ElementInfo nearestAncestor){
        if(node == null || result.size() >= maxCount) return;
        ElementInfo ancestorForChildren = nearestAncestor;
        if(node.isVisibleToUser() && node.isClickable()){
            String label = extractLabel(node);
            if(label != null){
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if(!bounds.isEmpty() && !isDuplicateOfAncestor(label, bounds, nearestAncestor)){
                    cachedNodes.add(AccessibilityNodeInfo.obtain(node));
                    ElementInfo info = new ElementInfo(cachedNodes.size() - 1, label, bounds);
                    result.add(info);
                    ancestorForChildren = info;
                }
            }
        }
        int childCount = node.getChildCount();
        for(int i = 0; i < childCount && result.size() < maxCount; i++){
            AccessibilityNodeInfo child = node.getChild(i);
            if(child != null){
                collectClickableNodes(child, result, maxCount, ancestorForChildren);
                child.recycle();
            }
        }
    }

    //整行可点击的容器自己没有文字时，标签是从内部子控件"借"来的（见extractLabel）；
    //如果那个被借用文字的子控件自己也单独可点击（很常见，比如外层行+里面的文字
    //TextView都标了clickable=true），同一个视觉按钮就会在列表里出现两次：一次是
    //外层容器（范围更大），一次是内部子控件（范围被外层完全包住）。这里按"标签
    //相同 且 范围被上一个已收录的祖先元素包住"过滤掉后者——包住关系同时覆盖了
    //"范围完全相同"和"子控件范围更小"两种情况，不会误伤真正独立、不重叠的子元素
    //（比如同一行里图标按钮和文字各自可点、标签也不同的情况）。
    private boolean isDuplicateOfAncestor(String label, Rect bounds, ElementInfo ancestor){
        if(ancestor == null || !label.equals(ancestor.label)) return false;
        Rect ancestorBounds = new Rect(ancestor.left, ancestor.top, ancestor.right, ancestor.bottom);
        return ancestorBounds.contains(bounds);
    }

    //很多可点击项本身没有文字（比如整块都能点的列表条目），文字其实放在它
    //不可点击的子控件里——自己没有的话往下找几层，捞一个能显示的名字出来。
    private String extractLabel(AccessibilityNodeInfo node){
        String own = textOf(node);
        if(own != null) return own;
        return findLabelInChildren(node, 3);
    }

    private String textOf(AccessibilityNodeInfo node){
        CharSequence text = node.getText();
        if(!TextUtils.isEmpty(text)) return text.toString().trim();
        CharSequence desc = node.getContentDescription();
        if(!TextUtils.isEmpty(desc)) return desc.toString().trim();
        return null;
    }

    private String findLabelInChildren(AccessibilityNodeInfo node, int depth){
        if(node == null || depth <= 0) return null;
        int childCount = node.getChildCount();
        for(int i = 0; i < childCount; i++){
            AccessibilityNodeInfo child = node.getChild(i);
            if(child == null) continue;
            String label = textOf(child);
            if(label == null) label = findLabelInChildren(child, depth - 1);
            child.recycle();
            if(label != null) return label;
        }
        return null;
    }

    /**
     * 点击/screenElements里返回的第index个元素。两次刷新之间屏幕如果已经变了，
     * 这个节点可能已经失效，performAction会返回false，调用方按失败处理即可，
     * 不会抛异常导致服务端崩溃。
     */
    public synchronized boolean clickElement(int index){
        if(index < 0 || index >= cachedNodes.size()) return false;
        AccessibilityNodeInfo node = cachedNodes.get(index);
        try {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception e){
            return false;
        }
    }

    //多任务(APP_SWITCH/Recents)键：跟电源键属于同一类"系统级拦截键"，往
    //InputConnection注入的合成按键根本到不了PhoneWindowManager那一层，之前
    //只能靠ADB(adb shell input keyevent)兜底。但GLOBAL_ACTION_RECENTS是安卓
    //标准的无障碍API，任何装了无障碍服务的App都能直接调用系统的"最近任务"
    //界面——用户反馈装了第三方按键映射App后不需要ADB权限也能触发多任务键，
    //用的正是这条路径。既然本App已经有ScreenAccessibilityService这个无障碍
    //服务（"元素列表"功能在用），直接复用即可，不用再依赖ADB。
    public boolean performRecents(){
        try {
            return performGlobalAction(GLOBAL_ACTION_RECENTS);
        } catch (Exception e){
            return false;
        }
    }
}
