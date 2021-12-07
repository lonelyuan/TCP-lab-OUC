/***************************2.1: ACK/NACK
 **************************** Feng Hong; 2015-12-09*/

package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Sender extends TCP_Sender_ADT {

    /**
     * 待发送的TCP数据报
     */
    private TCP_PACKET tcpPack;

    public TCP_Sender() {
        super();
        super.initTCP_Sender(this);
    }

    /**
     * 封装应用层数据，产生TCP数据报
     *
     * @param dataIndex 数据序号（不是包号
     * @param appData 应用层数据
     */
    @Override
    public void rdt_send(int dataIndex, int[] appData) {
        tcpH.setTh_seq(dataIndex * appData.length + 1);//包序号设置为字节流号
        tcpS.setData(appData);
        tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);
        tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
        tcpPack.setTcpH(tcpH);
        udt_send(tcpPack);
        waitACK();
    }

    /**
     * 不可靠发送：通过不可靠传输信道发送；仅需修改错误标志
     * @param stcpPack 将打包好的TCP数据报
     */
    @Override
    public void udt_send(TCP_PACKET stcpPack) {
        tcpH.setTh_eflag((byte) 1);
        client.send(stcpPack);
    }

    /**
     * 循环检查ackQueue
     */
    @Override
    public void waitACK() {
//        System.out.println("[*] waiting: " + Thread.currentThread().getName());
        while (true) {
            if (!ackQueue.isEmpty()) {
                int currentAck=ackQueue.poll();
                int pack_seq = tcpPack.getTcpH().getTh_seq();
                if (currentAck == pack_seq) {
                    System.out.println("[+] Clear: " + pack_seq);
                    break;
                } else {
                    System.out.println("[+] Retransmit: " + pack_seq);
                    udt_send(tcpPack);
                }
            }
        }
    }

    @Override
    //接收到ACK报文：检查校验和，将确认号插入ack队列;NACK的确认号为－1；不需要修改
    public void recv(TCP_PACKET recvPack) {
        ackQueue.add(recvPack.getTcpH().getTh_ack());
        System.out.println("[+] ackQueue: " + ackQueue);
    }
}
