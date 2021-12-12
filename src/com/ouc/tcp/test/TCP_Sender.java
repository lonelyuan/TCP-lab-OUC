package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.message.*;

import java.util.*;

/**
 * RDT4.3 TCP
 *
 * @author czy
 */
public class TCP_Sender extends TCP_Sender_ADT {

    private int N = 10; // 窗口容量
    private int sendBase = 1; // 窗口指针
    private final List<WindowItem> sendWindow; // 发送窗口
    private int unACKedHead; // 第一个未应答包序号
    private int rtt; // 估计的RTT


    /**
     * 发送窗口中的一项
     */
    static class WindowItem {
        private final TCP_PACKET tcpPack; // TCP报文段
        //        public Timer timer; // 计时器
        private int reTransCnt; // 重传计数器
        private int dupACKCnt; // 冗余ACK计数器
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
//            timer = new Timer();
            reTransCnt = 0;
            dupACKCnt = 0;
        }

        @Override
        public String toString() {
            return "I{" +
                    " Seq=" + Seq() +
                    ", reTransCnt=" + reTransCnt +
                    ", dupACKCnt=" + dupACKCnt +
                    ", pakStat=" + pakStat +
                    '}';
        }

        private int Seq() {
            return tcpPack.getTcpH().getTh_seq();
        }
    }


    public TCP_Sender() {
        super();
        super.initTCP_Sender(this);
        //RDT4.2 初始化窗口
        sendWindow = new ArrayList<>();
        unACKedHead = -1;
        // 单一重传计时器
        Timer t = new Timer();
        t.schedule(new TimerTask() {
            @Override
            public void run() {
                if (unACKedHead != -1) {
                    for (WindowItem I : sendWindow) {
                        if (I.Seq() == unACKedHead) {
                            System.out.println("{S}[!] Retransmit " + unACKedHead);
                            I.reTransCnt++;
                            udt_send(I.tcpPack);
                            break;
                        }
                    }
                }
            }
        }, 10, 10); // 0.01秒后重传
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
//                            System.out.println("{S}[!] Retransmit " + I.Seq());
//                            udt_send(I.tcpPack);
//                        }
//                    } else if (I.pakStat == 3) { // 有序，期待下一个包
//                        if (I.Seq() == expSeq && order) {
//                            expSeq = I.Seq() + I.tcpPack.getTcpS().getDataLengthInByte() / 4;
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
                    System.out.print("○" + I.Seq());
                    break;
                case 2:
                    System.out.print("◐" + I.Seq());
                    break;
                case 3:
                    System.out.print("◉" + I.Seq());
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
//        sendPack(I);
        udt_send(tcpPack);
        sendWindow.add(I);
        System.out.println("{S}[+] Add to Window");
        printWindow();
    }

    /**
     * --*应用层接口*--
     * 更新ACK状态，更新待重发项，驱动窗口移动
     *
     * @param recvPack 发送方只接收到ACK报文
     */
    @Override
    public void recv(TCP_PACKET recvPack) {
        int ackSeq = recvPack.getTcpH().getTh_ack();
        System.out.println("{S}[+] received ACK:" + ackSeq);
        // 第一循环 应答报文
        for (WindowItem I : sendWindow) {
            if (I.Seq() == ackSeq) { // 找到对应报文
                if (I.pakStat == 2){ // 首次应答
                    I.pakStat = 3;
                    System.out.println("{S}[+] marked: " + I.Seq());
                    break;
                }else { // 冗余ACK
                    I.dupACKCnt++;
                }
            }
        }
        // 第二循环 检查窗口有序段，直到第一个未应答
        int orderNum = 0; // 可释放包数量
        int expSeq = sendBase; // 期望的包号
        for (WindowItem I : sendWindow) {
            if (I.pakStat == 3 && I.Seq() == expSeq) { // 有序到达，准备推动窗口 （假定待发送包按序到达）
                expSeq = I.Seq() + I.tcpPack.getTcpS().getDataLengthInByte() / 4;
                orderNum++;
            } else {// 未到达，准备重传，不再检查后续的包
                unACKedHead = I.Seq();
//            if (I.Seq() != I.tcpPack.getTcpH().getTh_seq()){
//                    System.out.println("{S}[!] wsnd: " + I + " " + I.tcpPack.getTcpH().getTh_seq());
//                }
                break;
            }
        }
        // 第三循环 更新窗口
        if (orderNum > 0) { // 更新窗口
            System.out.println("{S}[*] Free " + orderNum);
            WindowItem I = sendWindow.get(orderNum - 1);
            sendBase = I.Seq() + I.tcpPack.getTcpS().getDataLengthInByte() / 4;
            while (orderNum > 0) {
                sendWindow.remove(0);
                orderNum--;
            }
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
