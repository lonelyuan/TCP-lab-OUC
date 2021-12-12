package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.message.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RDT4.2 SR
 *
 * @author czy
 */
public class TCP_Sender extends TCP_Sender_ADT {

    private int N = 10; // 窗口容量
    private int sendBase = 1; // 窗口指针
    private final List<WindowItem> sendWindow; // 发送窗口

    /**
     * 发送窗口中的一项
     */
    static class WindowItem {
        private final TCP_PACKET tcpPack; // TCP报文段
        private final int pakSeq; // 包序号
        public Timer timer; // 计时器
        private int reTransCnt; // 重传计数器
        private final int dupACKCnt; // 冗余ACK计数器
        /**
         * 包状态
         * 可用未发送:1 | 发送未确认:2 | 已确认:3 | 不可用:0
         */
        private int pakStat;

        WindowItem(TCP_PACKET pak) {
            tcpPack = pak;
            pakStat = 2; // 刚加入的包为发送未确认
            timer = new Timer();
            pakSeq = pak.getTcpH().getTh_seq();
            reTransCnt = 0;
            dupACKCnt = 0;
        }

        public int get_seq() {
            return this.tcpPack.getTcpH().getTh_seq();
        }

        public void set_stat(int stat) {
            this.pakStat = stat;
        }
    }


    public TCP_Sender() {
        super();
        super.initTCP_Sender(this);
        //RDT4.2 初始化窗口
        sendWindow = new ArrayList<>();
//        mainLoop();
    }

//    /**
//     * 主循环，非阻塞运行
//     */
//    private void mainLoop() {
//        Timer mainTimer = new Timer();
//        TimerTask checkWindow = new TimerTask() {
//            @Override
//            public void run() {
//                boolean order = true; // 是否有序
//                int orderNum = 0; // 可释放包数量
//                int expSeq = sendBase; // 期望的包号
//                for (WindowItem I : sendWindow) {
//                    if (I.pakStat == 1) { // 未发送，发送之
//                        udt_send(I.tcpPack);
//                        I.pakStat = 2;
//                    } else if (I.pakStat == 2) { // 未应答，准备重传
//                        checkACK(I);
//                        if (++I.reTransCnt % 5 == 0) { // 重传间隔略大于0.01*N
//                            System.out.println("{S}[!] Retransmit " + I.pakSeq);
//                            udt_send(I.tcpPack);
//                        }
//                    } else if (I.pakStat == 3) { // 有序，期待下一个包
//                        if (I.pakSeq == expSeq && order) {
//                            expSeq = I.pakSeq + I.tcpPack.getTcpS().getDataLengthInByte() / 4;
//                            orderNum += 1;
//                            System.out.println("{S}[*] orderNum " + orderNum + " expSeq " + expSeq);
//                        } else { // 失序，不再检查后续的包
//                            order = false;
//                        }
//                    }
//                }
//                if (orderNum > 0) { // 更新窗口
//                    System.out.println("{S}[*] Free " + orderNum);
//                    sendWindow.subList(0, orderNum).clear();
//                    sendBase = expSeq;
//                    printWindow();
//                }
//            }
//        };
//        mainTimer.schedule(checkWindow, 0, 10); // 每0.01秒检查一次窗口
//    }

//    /**
//     * 检查ACK
//     *
//     * @param I 未确认的包
//     */
//    private void checkACK(WindowItem I) {
//        for (int i : ackQueue) {
//            if (I.pakSeq == i) {
//                I.pakStat = 3;
//                break;
//            }
//        }
//    }

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
                    System.out.println("{S}[!] Retransmit " + I.pakSeq);
                    udt_send(tcpPack);
                } else if (I.pakStat == 3) {
                    System.gc();
                    cancel();
                }
            }
        }, 10, 10); // 0.01秒后重传
    }

    /**
     * 展示窗口状态
     * ○|可用未发送|1
     * ◐|发送未确认|2
     * ◉|已确认   |3
     */
    public void printWindow() {
        if (sendWindow.size() == 0) return;
        System.out.println("------ SendWindow ------");
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
        System.out.printf("\n---- SendBase:%05d ----\n", sendBase);
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
        tcpH.setTh_seq(dataIndex * appData.length + 1);//包序号设置为字节流号
        tcpS.setData(appData);
        TCP_PACKET tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);
        tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
        tcpPack.setTcpH(tcpH);
        //RDT4.2 送入发送窗口
        WindowItem I = new WindowItem(tcpPack);
        sendPack(I);
        sendWindow.add(I);
        System.out.println("{S}[+] Add to Window");
        printWindow();
    }

    /**
     * --*应用层接口*--
     * 收到ACK，驱动窗口移动
     *
     * @param recvPack 发送方只接收到ACK报文
     */
    @Override
    public void recv(TCP_PACKET recvPack) {
        int ackSeq = recvPack.getTcpH().getTh_ack();
        System.out.println("{S}[+] received ACK:" + ackSeq);
        boolean order = true; // 是否有序
        int orderNum = 0; // 可释放包数量
        int expSeq = sendBase; // 期望的包号
        for (WindowItem I : sendWindow) {
            if (I.pakStat == 2) { // 应答
                if (I.get_seq() == ackSeq) {
                    I.set_stat(3);
                }
            } else if (I.pakStat == 3) { // 有序，期待下一个包
                if (I.pakSeq == expSeq && order) {
                    expSeq = I.pakSeq + I.tcpPack.getTcpS().getDataLengthInByte() / 4;
                    orderNum += 1;
//                    System.out.println("{S}[*] orderNum " + orderNum + " expSeq " + expSeq);
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
