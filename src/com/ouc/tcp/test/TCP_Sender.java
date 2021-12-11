package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.message.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * RDT4.2 SR
 *
 * @author czy
 */
public class TCP_Sender extends TCP_Sender_ADT {

    private int sendBase = 1; // 窗口指针
    private final List<WindowItem> sendWindow; // 发送窗口

    /**
     * 发送窗口中的一项
     */
    static class WindowItem {
        private final TCP_PACKET tcpPack; // TCP报文段
        private final int pakSeq; // 包序号
        private final Timer timer; // 独立计时器
        /**
         * 包状态
         * 可用未发送:1 | 发送未确认:2 | 已确认:3 | 不可用:0
         */
        private int pakStat;

        WindowItem(TCP_PACKET pak) {
            tcpPack = pak;
            pakStat = 1; // 刚加入的包为可用未发送
            pakSeq = pak.getTcpH().getTh_seq();
            timer = new Timer();
        }

        public int get_seq() {
            return this.pakSeq;
        }

        public void set_stat(int stat) {
            this.pakStat = stat;
        }
    }


    public TCP_Sender() {
        super();
        super.initTCP_Sender(this);
        //RDT4.2 初始化窗口
        // 窗口容量
        int n = 10;
        sendWindow = new ArrayList<>(n);
        mainLoop();
    }

    /**
     * 主循环，非阻塞运行
     */
    private synchronized void mainLoop() {
        Timer mainTimer = new Timer();
        TimerTask checkWindow = new TimerTask() {
            @Override
            public synchronized void run() {
                boolean order = true;
                int orderNum = 0; // 可释放包数量
                int expSeq = sendBase; // 期望的包号
                for (WindowItem I : sendWindow) {
                    if (I.pakStat == 1) { // 未发送，发送之
                        sendPack(I);
                    }else if (I.pakStat == 3) { // 有序，期待下一个包
                        if (I.pakSeq == expSeq && order) {
                            expSeq = I.pakSeq + I.tcpPack.getTcpS().getDataLengthInByte() / 4;
                            orderNum += 1;
                            System.out.println("{S}[*] orderNum " + orderNum + " expSeq " + expSeq);
                        } else { // 失序，不再检查后续的包
                            order = false;
                        }
                    }
                }
                if (orderNum > 0) { // 更新窗口
                    System.out.println("{S}[*] Free " + orderNum);
                    sendWindow.subList(0, orderNum).clear();
                    sendBase = expSeq;
                    printWindow();
                }
            }
        };
        mainTimer.schedule(checkWindow, 0, 10); // 每0.01秒检查一次窗口
    }

    /**
     * 发送包并配置独立计时器
     *
     * @param I 窗口项
     */
    private synchronized void sendPack(WindowItem I) {
        TCP_PACKET tcpPack = I.tcpPack;
        udt_send(tcpPack);
        I.pakStat = 2;
        I.timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (I.pakStat == 2) {
                    udt_send(tcpPack);
                }else if (I.pakStat == 3){
                    this.cancel();
                }
            }
        }, 100, 100); // 0.1秒后重传
    }

    /**
     * 展示窗口状态
     * ○|可用未发送|1
     * ◐|发送未确认|2
     * ◉|已确认   |3
     */
    public synchronized void printWindow() {
        if (sendWindow.size() == 0) return;
        System.out.println("---- SendWindow ----");
        for (WindowItem I : sendWindow) {
            switch (I.pakStat) {
                case 1:
                    System.out.print("○" + I.pakSeq);
                    break;
                case 2:
                    System.out.print("◐" + I.pakSeq);
                    break;
                case 3:
                    System.out.print("◉" + I.pakSeq);
                    break;
            }

        }
        System.out.printf("\n---- base:%05d ----\n", sendBase);
    }


    /**
     * --*应用层接口*--
     * 封装数据，产生TCP数据报
     *
     * @param dataIndex 数据序号（不是包号
     * @param appData   应用层数据
     */
    @Override
    public synchronized void rdt_send(int dataIndex, int[] appData) {
        // 构建TCP包
        tcpH.setTh_seq(dataIndex * appData.length + 1);//包序号设置为字节流号
        tcpS.setData(appData);
        TCP_PACKET tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);
        tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
        tcpPack.setTcpH(tcpH);
        //RDT4.2 送入发送窗口
        sendWindow.add(new WindowItem(tcpPack));
        System.out.println("{S}[+] Add to Window");
        printWindow();
    }

    /**
     * --*应用层接口*--
     * 更新ACK队列
     *
     * @param recvPack 发送方只接收到ACK报文
     */
    @Override
    public void recv(TCP_PACKET recvPack) {
        int ackSeq = recvPack.getTcpH().getTh_ack();
        for (WindowItem I : sendWindow) {
            if (I.get_seq() == ackSeq) {
                I.set_stat(3);
            }
        }
    }

    /**
     * 不可靠发送：通过不可靠传输信道发送
     * !!仅需修改错误标志!!
     *
     * @param Packet 打包好的TCP数据报
     */
    @Override
    public void udt_send(TCP_PACKET Packet) {
        tcpH.setTh_eflag((byte) 3); // RDT4.2: 位错+丢包+失序
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
