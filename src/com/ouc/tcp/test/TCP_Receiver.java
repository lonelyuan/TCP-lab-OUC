package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;


/**
 * RDT4.2 SR
 *
 * @author czy
 */
public class TCP_Receiver extends TCP_Receiver_ADT {

    private final int N = 10; // 窗口容量
    private int recvBase = 1; // 窗口指针
    private SortedSet<WindowItem> recvWindow; // 发送窗口

    /**
     * 发送窗口中的一项
     */
    class WindowItem {
        private final TCP_PACKET tcpPack; // TCP报文段
        private final int pakSeq; // 包序号
        /**
         * 包状态
         * 期待未收到:1|失序未确认:2|已确认:3|不可用:0
         */
        private int pakStat;

        WindowItem(TCP_PACKET pak) {
            tcpPack = pak;
            pakStat = 2; // 刚加入的包默认为失序未确认态
            pakSeq = pak.getTcpH().getTh_seq();
        }

        public int dataLen() {
            return this.tcpPack.getTcpS().getDataLengthInByte() / 4;
        }
    }

    public TCP_Receiver() {
        super();
        super.initTCP_Receiver(this);
        //RDT4.2 初始化窗口
        // 数据结构：红黑树
        recvWindow = new TreeSet<>(Comparator.comparingInt(o -> o.tcpPack.getTcpH().getTh_seq()));
    }


    /**
     * 生成ACK报文并返回
     *
     * @param recvSeq  返回的序列号
     * @param recvPack 用于取地址
     */
    private void sendACK(int recvSeq, TCP_PACKET recvPack) {
        tcpH.setTh_ack(recvSeq);
        TCP_PACKET ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
        tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
        reply(ackPack);
    }

    /**
     * 展示窗口状态
     * ◎|期待未收到|1
     * ◑|失序未确认|2
     * ◉|已确认   |3
     */
    public synchronized void printWindow() {
        if (recvWindow.isEmpty()) {
            return;
        }
        System.out.println("------ RecvWindow ------");
        for (WindowItem I : recvWindow) {
            switch (I.pakStat) {
                case 1:
                    System.out.print("◎" + I.pakSeq);
                    break;
                case 2:
                    System.out.print("◑" + I.pakSeq);
                    break;
                case 3:
                    System.out.print("◉" + I.pakSeq);
                    break;
            }
        }
        System.out.printf("\n---- RecvBase:%05d ----\n", recvBase);
    }

    /**
     * 检查窗口的有序部分，交付数据
     */
    private synchronized void updateWindow() {
        WindowItem I;
        boolean ordered = true;
        int n = 0;
        while (ordered && !recvWindow.isEmpty()) {
            I = recvWindow.first();
//            System.out.println("{R}[+] first " + I.pakSeq + " Base " + recvBase);
            if (I.pakSeq == recvBase) { // 下一个块有序
                dataQueue.add(I.tcpPack.getTcpS().getData());
                recvBase += I.dataLen();
                recvWindow.remove(I);
                n++;
            } else {
                ordered = false;
                System.out.println("{R}[*] Free " + n);
                printWindow();
            }
        }
        if (dataQueue.size() == 20) {
            deliver_data();
        }
    }

    /**
     * --*应用层接口*--
     * 生成ACK，交付数据
     *
     * @param recvPack 收到的包
     */
    @Override
    public synchronized void rdt_recv(TCP_PACKET recvPack) {
        int recvSeq = recvPack.getTcpH().getTh_seq();
        //RDT4.2 判断窗口
        if (CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
            sendACK(recvSeq, recvPack);
            if (recvSeq < recvBase) { // [recvBase - N, recvBse - 1] 窗口前，重发ACK
                System.out.println("{R}[*] duplicate ACK");
            } else if (recvSeq == recvBase) { // 按序到达，推动窗口
                System.out.println("{R}[*] Push Window");
                recvWindow.add(new WindowItem(recvPack));
                updateWindow();
            } else { // [recvBase, recvBse + N] 窗口内，缓存之
                sendACK(recvSeq, recvPack);
                recvWindow.add(new WindowItem(recvPack));
                System.out.println("{R}[+] Add to Window");
                printWindow();
            }
//            else { // 丢弃
//                System.out.println("[!] dropped " + recvSeq);
//            }
        } else {
            System.out.println("{R}[!] check sum failed!");
        }
    }


    /**
     * 交付数据（将数据写入文件）
     * !!不需要修改!!
     */
    @Override
    public void deliver_data() {
        File fw = new File("recvData.txt");
        BufferedWriter writer;
        try {
            writer = new BufferedWriter(new FileWriter(fw, true));
            //循环检查data队列中是否有新交付数据
            while (!dataQueue.isEmpty()) {
                int[] data = dataQueue.poll();
                //将数据写入文件
                for (int datum : data) {
                    writer.write(datum + "\n");
                }
                writer.flush();        //清空输出缓存
            }
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 回复ACK报文段
     * !!仅需修改错误标志!!
     *
     * @param replyPack 返回包
     */
    @Override
    public void reply(TCP_PACKET replyPack) {
        tcpH.setTh_eflag((byte) 3); // RDT4.2: 位错+丢包+失序
        client.send(replyPack);
    }

}
