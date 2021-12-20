package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;


/**
 * RDT4.3 TCP
 *
 * @author czy
 */
public class TCP_Receiver extends TCP_Receiver_ADT {

    private int recvBase = 1; // 窗口指针
    private final SortedSet<WindowItem> recvWindow; // 发送窗口

    /**
     * RDT4.2 发送窗口项
     */
    static class WindowItem {
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
        @Override
        public String toString() {
            return "I{" +
                    " Seq=" + Seq() +
                    ", pakStat=" + pakStat +
                    '}';
        }

        private int Seq() {
            return tcpPack.getTcpH().getTh_seq();
        }

        public int dataLen() {
            return this.tcpPack.getTcpS().getDataLengthInByte() / 4;
        }
    }

    public TCP_Receiver() {
        super();
        super.initTCP_Receiver(this);
        //RDT4.2 初始化窗口
        recvWindow = new TreeSet<>(Comparator.comparingInt(o -> o.tcpPack.getTcpH().getTh_seq()));
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
        System.out.println("{R}[+] received data:" + recvSeq + " Base " + recvBase);
        if (checkPak(recvPack)) return; // 校验包
        // [recvBase - N, recvBse - 1] 窗口前，重发ACK
        if (recvSeq < recvBase) {
            sendACK(recvSeq, recvPack);
            System.out.println("{R}[*] duplicate ACK" + recvSeq);
            return;
        }
        // [recvBase, recvBse + N] 窗口内，缓存之
        recvWindow.add(new WindowItem(recvPack));
        // RDT4.3 累计确认
        updateWindow(recvPack);
    }

    private boolean checkPak(TCP_PACKET recvPack) {
        if (CheckSum.computeChkSum(recvPack) != recvPack.getTcpH().getTh_sum()) { // 校验
            System.out.println("{R}[!] check sum failed!");
            return true;
        }
        return false;
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
        if (recvWindow.size() == 0) return;
        StringBuilder sb = new StringBuilder().append("------ {recvWindow} ------\n");
        sb.append("-- Base: ").append(recvBase);
        for (WindowItem I : recvWindow) {
            switch (I.pakStat) {
                case 1:
                    sb.append("◎").append(I.Seq());
                    break;
                case 2:
                    sb.append("◑").append(I.Seq());
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
     * 检查窗口有序部分，交付数据
     */
    private synchronized void updateWindow(TCP_PACKET recvPack) {
        int expSeq = recvBase; // 下一个期待的包
        while (!recvWindow.isEmpty()) {
            WindowItem I = recvWindow.first();
            // System.out.println("{R}[+] first " + I.pakSeq + " Base " + recvBase);
            if (I.pakSeq == expSeq) { // 下一个块有序
                I.pakStat = 3;
                dataQueue.add(I.tcpPack.getTcpS().getData());
                recvWindow.remove(I);
                expSeq++;
            } else {
                break;
            }
        }
        System.out.println("{R}[*] Free " + (expSeq - recvBase));
        recvBase = expSeq;
        printWindow();
        sendACK(recvBase - 1, recvPack); // 累计确认
        if (dataQueue.size() == 20) {
            deliver_data();
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
        tcpH.setTh_eflag((byte) 7); // RDT4.2: 位错+丢包+失序
        client.send(replyPack);
    }

}
