package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.message.*;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.toIntExact;

/**
 * RDT4.3 TCP
 *
 * @author czy
 */
public class TCP_Sender extends TCP_Sender_ADT {

    /**
     * RDT5.2 阻塞控制状态
     */
    enum CongStat {
        SS, // SlowStart 慢开始
        CA, // CongestionAvoidance 拥塞避免
    }

    /**
     * RDT4.3 重传任务
     */
    class ReTransTask extends TimerTask {
        @Override
        public void run() {
            System.out.println("{S}[SS] congestion!");
            Stat = CongStat.SS;
            ReFlag = true; // 准备GBN
            ssthresh = cwnd / 2;
            cwnd = 1;
            // 重传一个包，设重传头
            Iterator<WindowItem> it = sendWindow.iterator();
            if (it.hasNext()) {
                WindowItem I = it.next();
                udt_send(I.tcpPack);
                sendBase = I.Seq();
                nextReSeq = sendBase + 1;
            }
        }
    }

    /**
     * RDT4.2 发送窗口项
     */
    static class WindowItem {
        private final TCP_PACKET tcpPack; // TCP报文段
        private long start; // 开始时间
        private int reTransCnt; // 重传计数器
        /**
         * 包状态
         * 可用未发送:1 | 发送未确认:2 | 已确认:3 | 不可用:0
         */
        private int pakStat;

        WindowItem(TCP_PACKET pak) {
            TCP_PACKET P = null;
            try {
                P = pak.clone();
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
            }
            tcpPack = P;
            pakStat = 2; // 刚加入的包为发送未确认
            reTransCnt = 0;
        }

        @Override
        public String toString() {
            return "I{" +
                    " Seq=" + Seq() +
                    ", reTransCnt=" + reTransCnt +
                    ", pakStat=" + pakStat +
                    '}';
        }

        private int Seq() {
            return tcpPack.getTcpH().getTh_seq();
        }

        private int DataLen() {
            return tcpPack.getTcpS().getDataLengthInByte() / 4;
        }

        private int Next() {
            return this.Seq() + this.DataLen();
        }
    }

    // RDT4.2 发送方缓存
    private long sendBase = 1; // 窗口指针
    private final Queue<WindowItem> sendWindow; // 发送窗口
    private long nextSeq = 1; // 下一个要发的包
    // RDT4.2 超时重传
    private long nextReSeq = 1; // 下一个要重发的包
    private boolean ReFlag = false; // 超时重发状态
    // RDT4.3 单计时器
    private final Timer timer;
    private ReTransTask task;
    // RDT4.3 实时RTT
    private short iRTT = 1000; // 超时时延 单位：ms
    private short eRTT; // RTT估计 单位：ms
    private short dRTT; // RTT波动 单位：ms
    // RDT4.3 快重传
    private long lastACK = 0; // 上个收到的ACK
    private short dupACK = 0; // 冗余ACK计数器
    // RDT5.1 阻塞控制
    private volatile CongStat Stat = CongStat.SS;
    private int cwnd = 1; // 阻塞窗口
    private int ssthresh = 655; // 慢启动阈值

    public TCP_Sender() {
        super();
        super.initTCP_Sender(this);
        //RDT4.2 初始化窗口
        sendWindow = new LinkedBlockingQueue<>();
        timer = new Timer();
    }

    /**
     * 展示窗口状态
     * ○|可用未发送|1
     * ◐|发送未确认|2
     * ◉|已确认   |3
     */
    public void printWindow() {
        if (sendWindow.size() == 0) return;
        StringBuilder sb = new StringBuilder().append("------ {SendWindow} ------\n");
        sb.append("-- Base: ").append(sendBase).append(" -- Next: ").append(nextSeq)
                .append("\n-- ").append(Stat).append(" -- CNWD: ").append(cwnd).append(" -- ssthresh: ").append(ssthresh).append("\n-- ");
        for (WindowItem I : sendWindow) {
            switch (I.pakStat) {
                case 1:
                    sb.append("○").append(I.Seq());
                    break;
                case 2:
                    sb.append("◐").append(I.Seq());
                    break;
                case 3:
                    sb.append("◉").append(I.Seq());
                    break;
            }

        }
        sb.append("\n------------------------\n");
        System.out.println(sb);
    }

    /**
     * --*应用层接口*--
     * 封装数据，产生TCP数据报
     *
     * @param dataIndex 数据序号（不是包号
     * @param appData   应用层数据
     */
    @Override
    public void rdt_send(int dataIndex, int[] appData) {
        // 构建TCP包
        tcpH.setTh_seq(dataIndex + 1);//因为数据包定长，简化序号
        tcpS.setData(appData);
        TCP_PACKET tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);
        tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
        tcpPack.setTcpH(tcpH);
        // RDT5.1 阻塞控制
        while (true) {
            if (nextSeq < sendBase + cwnd && !ReFlag) {
                // RDT4.2 送入发送窗口
                WindowItem I = new WindowItem(tcpPack);
                udt_send(tcpPack);
                // RDT4.3 RTT
                I.start = System.currentTimeMillis();
                sendWindow.add(I);
                System.out.println("{S}[+] Add to Window");
                printWindow();
                // RDT4.2 窗口头堵塞，准备重传
                if (sendBase == nextSeq) {
                    task = new ReTransTask();
                    timer.schedule(task, iRTT, iRTT);
                }
                nextSeq += 1;
                break;
            }
        }
    }

    /**
     * --*应用层接口*--
     * 响应ACK，更新窗口，触发状态切换
     *
     * @param recvPack 发送方只接收到ACK报文
     */
    @Override
    public void recv(TCP_PACKET recvPack) {
        int ackSeq = recvPack.getTcpH().getTh_ack();
        System.out.println("{S}[+] received ACK:" + ackSeq);
        if (checkPak(recvPack)) return; // 校验包
        // 1. 快重传
        checkDupACK(ackSeq);
        // 2. 窗口前ACK
        if (ackSeq < sendBase) {
            System.out.println("{S}[+] received ACK:" + ackSeq);
            return;
        }
        // 3. 累计确认
        slideWin(ackSeq + 1 - sendBase);
        sendBase = ackSeq + 1;
        task.cancel();
        // 4. 重启计时器
        if (sendBase != nextSeq) {
            task = new ReTransTask();
            timer.schedule(task, iRTT, iRTT);
        }
        // 5. 阻塞控制
        if (cwnd >= ssthresh) { // CA: 加性增
            Stat = CongStat.CA;
            System.out.println("{S}[CA] addictive: " + ssthresh);
            cwnd += 1;
        } else {// SS: 指数增
            System.out.println("{S}[SS] exponential : " + ssthresh);
            cwnd *= 2;
        }
        // 6. 触发重传
        if (ReFlag) {
            reSendAll();
        }
    }

    /**
     * GBN重传
     */
    private void reSendAll() {
        if (nextReSeq < sendBase) { // 无需重传
            nextReSeq = sendBase;
        } else {
            int ReNum = toIntExact(sendBase + cwnd - nextReSeq);//重传数量
            System.out.println("{S}[*] Resend: " + ReNum);
            Iterator<WindowItem> it = sendWindow.iterator();
            while (ReNum > 0 && it.hasNext()) {
                WindowItem I = it.next();
                I.reTransCnt++;
                udt_send(I.tcpPack);
                ReNum--;
            }
        }
        if (nextReSeq == nextSeq) { // 重传完毕
            ReFlag = false;
        }
    }

    /**
     * 累计确认窗口
     *
     * @param l 窗口滑动量
     */
    private void slideWin(long l) {
        WindowItem I;
        while (l > 0 && !sendWindow.isEmpty()) {
            I = sendWindow.poll();
            I.pakStat = 3;
            calcRTT(System.currentTimeMillis() - I.start);
            System.out.println("{S}[+] marked: " + I.Seq() + " iRTT: " + iRTT);
            l--;
        }
    }

    /**
     * 快速重传
     *
     * @param ackSeq ACK号
     */
    private void checkDupACK(int ackSeq) {
        if (ackSeq == lastACK) {
            dupACK += 1;
            if (dupACK == 3) {
                TCP_PACKET pkt = sendWindow.element().tcpPack;
                udt_send(pkt);
                // CA: 阈值折半
                cwnd = max(cwnd / 2, 1);
                ssthresh = cwnd;
                Stat = CongStat.CA;
                System.out.println("{S}[CA] Fast Rec: " + ackSeq);
                printWindow();
            }
        } else {
            lastACK = ackSeq;
            dupACK = 0;
        }
    }

    /**
     * 校验包
     *
     * @param recvPack 收到的包
     * @return 是否校验通过
     */
    private boolean checkPak(TCP_PACKET recvPack) {
        if (CheckSum.computeChkSum(recvPack) != recvPack.getTcpH().getTh_sum()) {
            System.out.println("{S}[!] check sum failed!");
            return true;
        }
        return false;
    }

    /**
     * 更新RTT
     *
     * @param RTT 本轮RTT
     */
    private void calcRTT(long RTT) {
        // 指数移动平均 的加权α
        float alpha = 0.125F;
        eRTT = (short) ((float) eRTT * (1 - alpha) + (float) RTT * alpha);
        // 指数移动平均 的加权β
        float beta = 0.25F;
        dRTT = (short) ((float) dRTT * (1 - beta) + (float) abs(RTT - eRTT) * beta);
        iRTT = (short) max(4 * eRTT + dRTT, 100); // 四倍太长
    }


    /**
     * 不可靠发送：通过不可靠传输信道发送
     * !!仅需修改错误标志!!
     *
     * @param Packet 打包好的TCP数据报
     */
    @Override
    public void udt_send(TCP_PACKET Packet) {
        tcpH.setTh_eflag((byte) 7); // RDT4.2: 位错+丢包+失序
        client.send(Packet);
    }


    /**
     * 你狗屁抽象方法
     */
    @Deprecated
    @Override
    public void waitACK() {
    }

}
