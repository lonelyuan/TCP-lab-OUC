/***************************2.1: ACK/NACK*****************/
/***** Feng Hong; 2015-12-09******************************/
package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;

public class TCP_Receiver extends TCP_Receiver_ADT {

	/**
	 * 序列号
	 */
	private int seq;

	public TCP_Receiver() {
		super();
		super.initTCP_Receiver(this);
	}

	/**
	 * 检查校验码，生成ACK
	 * @param recvPack 收到的包
	 */
	@Override
	public void rdt_recv(TCP_PACKET recvPack) {
		TCP_PACKET ackPack; //回复的ACK报文段
		if(CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
			tcpH.setTh_ack(recvPack.getTcpH().getTh_seq()); //ACK
			ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
			tcpH.setTh_sum(CheckSum.computeChkSum(ackPack)); //回复ACK报文段
			reply(ackPack);
			//将接收到的正确有序的数据插入data队列，准备交付
  			//System.out.println("[*] seq now:" + sequence);
 			//System.out.println("[*] seq recv:" + recvPack.getTcpH().getTh_seq());
			if(recvPack.getTcpH().getTh_seq() != seq){
				dataQueue.add(recvPack.getTcpS().getData());
				seq = recvPack.getTcpH().getTh_seq();
			}else {
				System.out.println("[!] seq repeated!");
			}
		}else{
			System.out.println("[!] check sum failed!");
			tcpH.setTh_ack(-1); // NAK
			ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
			tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
			reply(ackPack);
		}
		//交付数据（每20组数据交付一次）
		if(dataQueue.size() == 20) deliver_data();
	}

	@Override
	//交付数据（将数据写入文件）；不需要修改
	public void deliver_data() {
		//检查dataQueue，将数据写入文件
		File fw = new File("recvData.txt");
		BufferedWriter writer;
		try {
			writer = new BufferedWriter(new FileWriter(fw, true));
			//循环检查data队列中是否有新交付数据
			while(!dataQueue.isEmpty()) {
				int[] data = dataQueue.poll();
				//将数据写入文件
				for(int i = 0; i < data.length; i++) {
					writer.write(data[i] + "\n");
				}
				writer.flush();		//清空输出缓存
			}
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 回复ACK报文段
	 * @param replyPack 返回包
	 */
	@Override
	public void reply(TCP_PACKET replyPack) {
		//设置错误控制标志
		tcpH.setTh_eflag((byte)1);
		client.send(replyPack);
	}
	
}
