package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * RDT3.0 Timer
 *
 * @author czy
 */
public class TCP_Sender extends TCP_Sender_ADT {

    /**
     * 待发送的TCP数据报
     */
    private TCP_PACKET tcpPack;
    /**
     * 检测丢包的定时器
     */
    private UDT_Timer timer;

    /**
     * 重写定时器任务以打印超时事件
     */
    static class RetransTask extends UDT_RetransTask{
        private final Client senderClient;
        private final TCP_PACKET reTransPacket;

        public RetransTask(Client client, TCP_PACKET packet) {
            super(client, packet);
            this.senderClient = client;
            this.reTransPacket = packet;
        }

        @Override
        public void run() {
            System.out.println("[*] Time exceeded.");
            this.senderClient.send(this.reTransPacket);
        }
    }

    public TCP_Sender() {
        super();
        super.initTCP_Sender(this);
    }

    /**
     * 封装应用层数据，产生TCP数据报
     *
     * @param dataIndex 数据序号（不是包号
     * @param appData   应用层数据
     */
    @Override
    public void rdt_send(int dataIndex, int[] appData) {
        tcpH.setTh_seq(dataIndex * appData.length + 1);//包序号设置为字节流号
        tcpS.setData(appData);
        tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);
        tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
        tcpPack.setTcpH(tcpH);
        udt_send(tcpPack);

        //RDT3.0 设置定时器任务，非阻塞
        timer = new UDT_Timer();
        RetransTask reTrans = new RetransTask(client, tcpPack);
        timer.schedule(reTrans,3000,3000);

        waitACK();
    }

    /**
     * 不可靠发送：通过不可靠传输信道发送
     * !!仅需修改错误标志!!
     *
     * @param stcpPack 将打包好的TCP数据报
     */
    @Override
    public void udt_send(TCP_PACKET stcpPack) {
        tcpH.setTh_eflag((byte) 4); // RDT3.0: 位错+丢包
        client.send(stcpPack);
    }

    /**
     * 循环检查ackQueue, 收到ACK则检查是否正确，正确则退出循环，错误则重传
     */
    @Override
    public void waitACK() {
        while (true) {
            if (!ackQueue.isEmpty()) {
                int currentAck = ackQueue.poll();
                int pack_seq = tcpPack.getTcpH().getTh_seq();
                if (currentAck == pack_seq) {
                    System.out.println("[+] Finished: " + pack_seq);
                    //RDT3.0 定时器撤销
                    timer.cancel();
                    break;
                } else {
                    System.out.println("[+] Retransmit: " + pack_seq);
                    udt_send(tcpPack);
                }
            }
        }
    }

    /**
     * 检查校验和，将确认号插入ack队列
     * !!不需要修改!!
     *
     * @param recvPack 接收到ACK报文
     */
    @Override
    public void recv(TCP_PACKET recvPack) {
        ackQueue.add(recvPack.getTcpH().getTh_ack());
//        System.out.println("[+] ackQueue: " + ackQueue);
    }
}
