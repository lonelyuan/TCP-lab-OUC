# TCP实验报告


## 可靠数据传输RDT里程碑

为实现迭代式开发，所有实验代码均通过Git管理，下面选中了的版本号是在代码仓库中标记了的Git标签。

<img src="\..\assets\git_stat.png" alt="git_stat" style="zoom:50%;" />

- 1.0：理想模型——完全可靠信道
- [x]  2.0：ARQ：自动重传——比特差错
  
  - 返回ACK/NAK
  - 2.1：序号，应对ACK/NAK出错阻塞
      - 停等协议，序号只需要1位
  - [x] 2.2：去除NAK
      - 仅对上次正确传输的分组发送ACK
- [x] 3.0：超时重传——信道丢包
  
  - 发送方启动定时器
-  4.0：流水线化——交付失序
  
  - 缓存失序包，维护接收/发送窗口
  - 4.1 GBN：回退N步
  - [x] 4.2 SR：选择重传
  - [x] 4.3 TCP初版
    - 单计时器，累计确认，快速重传，动态RTT
  
- 5.0：阻塞控制——加强性能
  
  - Tahoe：慢启动，阻塞避免，快重传
  
  - [x] 5.1 Reno：快恢复

## 代码解释和Log说明

### RDT2.0 ARQ

使用校验和应对比特差错，使用自带的`java.util.zip.CRC32`包。其他代码保持初态。

```java
// /src/com/ouc/tcp/test/CheckSum.java#L9
public class CheckSum {
    /**
     * 计算TCP报文段校验和：只需校验TCP首部中的seq、ack和sum，以及TCP数据字段
     * @param tcpPack 要计算的包
     * @return crc校验和
     */
    public static short computeChkSum(TCP_PACKET tcpPack) {
        CRC32 crc = new CRC32();
        crc.update(tcpPack.getTcpH().getTh_seq());
        crc.update(tcpPack.getTcpH().getTh_ack());
        int[] data = tcpPack.getTcpS().getData();
        for (int datum : data) {
            crc.update(datum);
        }
        crc.update(Arrays.toString(tcpPack.getTcpS().getData()).getBytes());
        return (short) crc.getValue();
    }

```

发送端发送完报文后进入`waitACK()`并阻塞，收到错误的ACK则重传。

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L58
@Override
    public void waitACK() {
//        System.out.println("[*] waiting: " + Thread.currentThread().getName());
        while (true) {
            if (!ackQueue.isEmpty()) {
                int currentAck = ackQueue.poll();
                int pack_seq = tcpPack.getTcpH().getTh_seq();
                if (currentAck == pack_seq) {
                    System.out.println("[+] Finished: " + pack_seq);
                    break;
                } else {
                    System.out.println("[+] Retransmit: " + pack_seq);
                    udt_send(tcpPack);
                }
            }
        }
    }
```

##### Log

可以看到超出错后立即进行了重传。

#### ![rdt_v20](\..\assets\rdt_v20.png)

![rdt_v20_1](\..\assets\rdt_v20_1.png)

### RDT2.2  noNAK

消除NACK的方法是用冗余ACK代替。于是接收端需要维护上一个包的序号。

```java
// /src/com/ouc/tcp/test/TCP_Receiver.java#L35
        if (CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
            /* 省略正常生成ACK的代码 */
            if (recvPack.getTcpH().getTh_seq() != seq) {
                dataQueue.add(recvPack.getTcpS().getData());
                seq = recvPack.getTcpH().getTh_seq(); // update seq
            } else {
                System.out.println("[!] seq repeated!");
            }
        } else {
            System.out.println("[!] check sum failed!");
            tcpH.setTh_ack(seq); // duplicateACK
            ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
            tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
            reply(ackPack);
        }
```

##### Log

可以看到ACK出错也立即进行重传。

![rdt_v22_1](\..\assets\rdt_v22_1.png)

![rdt_v22_11](\..\assets\rdt_v22_11.png)

### RDT3.0 timer

发送方使用定时器应对丢包。计时器使用`UDT_Timer `类，开辟单独线程。

```java
    // /src/com/ouc/tcp/test/TCP_Receiver.java#L35
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
            System.out.println("[*] Time exceeded.");//重写定时器任务以打印超时事件
            this.senderClient.send(this.reTransPacket);
        }
    }
```

在发送方构造方法里调用`timer.schedule(reTrans,3000,3000);`，在正常收到包后调用`timer.cancel();`。

##### Log

可以看到，丢包3秒后出发了重传。

![rdt_v30_3](\..\assets\rdt_v30_3.png)

![rdt_v30_loss](\..\assets\rdt_v30_loss.png)

同样保持对位错的处理：

![rdt_v30_rdt2](\..\assets\rdt_v30_rdt2.png)

### RDT4.2 SR

为了应对失序，选择响应协议（SR）缓存失序到达的数据包。发送窗口定义如下：

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L24
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
    //... 省略get/set方法
}
```

对窗口的修改有：

- 发送方法用`sendWindow.add(new WindowItem(tcpPack));`增加窗口项；
- 接收方法用`I.set_stat(3);` 标记窗口状态。

而窗口的更新和数据交付统一交由`mainloop()`方法解决，该函数自发送方初始化后便一直运行。

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L64
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
                            break;
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
```

SR的初版为每个包配备计时器：

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L64
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
```

由于发送顺序不会变化，发送窗口的数据结构可以是队列，而在接收端，必须手动维护窗口有序，因此借鉴 Linux 的 TCP 实现，使用红黑树作为数据结构。

```java
recvWindow = new TreeSet<>(Comparator.comparingInt(o -> o.tcpPack.getTcpH().getTh_seq()));
```

在收到包时，分三种情况，按序到达推动窗口。

```java
    // /src/com/ouc/tcp/test/TCP_Receiver.java#L129
    @Override
    public synchronized void rdt_recv(TCP_PACKET recvPack) {
        int recvSeq = recvPack.getTcpH().getTh_seq();
        System.out.println("{R}[+] received data:" + recvSeq + " Base " + recvBase);
        //RDT4.2 判断窗口
        if (CheckSum.computeChkSum(recvPack) != recvPack.getTcpH().getTh_sum()) { // 校验
            System.out.println("{R}[!] check sum failed!");
            return;
        }
        if (recvSeq < recvBase) { // [recvBase - N, recvBse - 1] 窗口前，重发ACK
            sendACK(recvSeq, recvPack);
            System.out.println("{R}[*] duplicate ACK" + recvSeq);
        } else if (recvSeq == recvBase) { // 按序到达，推动窗口
            sendACK(recvSeq, recvPack);
            System.out.println("{R}[*] Push Window");
            recvWindow.add(new WindowItem(recvPack));
            updateWindow(); // 更新窗口，消除有序部分
        } else { // [recvBase, recvBse + N] 窗口内，缓存之
            sendACK(recvSeq, recvPack);
            recvWindow.add(new WindowItem(recvPack));
            System.out.println("{R}[+] Add to Window");
            printWindow();
        }
    }
```

##### Log

下列案例说明了SR协议可以应对数据报失序。

![rdt_v40_r1](\..\assets\rdt_v40_r1.png)

![rdt_v40_s1](\..\assets\rdt_v40_s1.png)

![rdt_v40_s2](\..\assets\rdt_v40_s2.png)

![rdt_v40_s22](\..\assets\rdt_v40_s22.png)

### RDT4.3 TCP

TCP初版的特点是，单计时器+累计确认+选择重传+动态RTT。由于 SR 中`mainloop()`遇到的并发问题，累计确认协议不再使用“主循环删窗口+应用层接口增/改窗口”的方法，只由应用层接口触发窗口更新。

*注：该版本代码已合并于RDT5.1，为方便叙述在RDT4.3中解释。*

##### 单计时器

基于`mainloop()`版本的单计时器实现有一个取巧的方法，每次循环固有一个时间`t`，因此为窗口项添加`reTranscnt`字段，每次循环将未确认项的计数器加一，到达阈值后重传。这样重传时间略大于`t*reTranscnt`。而不需要新计时器线程。参见：

由于后续版本消除了`mainloop()`，因此需要标记待重传的第一个包`nextReSeq`。如果能保证确认一个包就推动窗口，那么下一个要重传的包只会出现在包头，因此`sendBase == nextSeq`成为判别重传的重要标志。

重传任务定义：

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L31
class ReTransTask extends TimerTask {
    @Override
    public void run() {
        ReFlag = true; // 准备GBN重传
        Iterator<WindowItem> it = sendWindow.iterator();
        if (it.hasNext()) {
            WindowItem I = it.next();
            udt_send(I.tcpPack);
            sendBase = I.Seq();
            nextReSeq = sendBase + 1; // 重发一个包
        }
    }
}
```

触发重传的逻辑有：

- `rdt_send()`中：

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L183
if (sendBase == nextSeq) { // 发送完毕，准备重传
    task = new ReTransTask();
    timer.schedule(task, iRTT, iRTT);
}
nextSeq += 1; // 重传头后移
```

- `recv()`中：

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L212
slideWin(ackSeq + 1 - sendBase);
sendBase = ackSeq + 1;
task.cancel();
if (sendBase != nextSeq) { //不相等说明sendBase被更新，说明网络尚有传输能力，重启计时器
    task = new ReTransTask();
    timer.schedule(task, iRTT, iRTT);
}
```

##### 累计确认

因为要发送的数据包大小相等且按序到达，为了方便包号计算，后续版本将包号简化为数据包到来的次序。

使用累计确认更新窗口的实现如下：

```java
// /src/com/ouc/tcp/test/TCP_Receiver.java#L146
private void updateWindow(TCP_PACKET recvPack) {
    int expSeq = recvBase; // 下一个期待的包
    while (!recvWindow.isEmpty()) {
        WindowItem I = recvWindow.first();
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
```

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L262
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
```

累计确认自然和GBN搭配。确认超时后，准备重发所有未确认的包，具体重传时间则是在下一个ACK到来时。GBN重传的实现：

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L238
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
```

##### 快速重传

TCP加入了三次冗余ACK触发快速重传规则，且处理快速重传的优先级最高。

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L278
private void checkDupACK(int ackSeq) {
        if (ackSeq == lastACK) {
    dupACK += 1;
        if (dupACK == 3) {
            TCP_PACKET pkt = sendWindow.element().tcpPack;
            udt_send(pkt);
        }
    } else {
        lastACK = ackSeq;
        dupACK = 0;
    }
}
```

##### 动态RTT

根据发包前和收到对应的ACK之后的系统时间，计算出该包的RTT，平均RTT根据公式计算即可：

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L316
private void calcRTT(long RTT) {
    // 指数移动平均 的加权α
    float alpha = 0.125F;
    eRTT = (short) ((float) eRTT * (1 - alpha) + (float) RTT * alpha);
    // 指数移动平均 的加权β
    float beta = 0.25F;
    dRTT = (short) ((float) dRTT * (1 - beta) + (float) abs(RTT - eRTT) * beta);
    iRTT = (short) max(4 * eRTT + dRTT, 100); // 不小于100ms
}
```

但由于测试环境是本地，RTT约等于0，动态RTT并无用武之地。

##### Log

如下图，累计确认可以消除单个ACK丢失的影响。

![rdt_v43_delay_wrong](\..\assets\rdt_v43_delay_wrong.png)

另外，图中包951出现位错，发送方窗口停止推动，发送冗余ACK。

![rdt_v43_error_log](\..\assets\rdt_v43_error_log.png)

在第三个冗余ACK到达时，触发快速重传。

![rdt_v50_wrong3](\..\assets\rdt_v50_wrong3.png)

重传成功，发送方累计确认4个包。

![rdt_v50_wrong4](\..\assets\rdt_v50_wrong4.png)

同时发送方log：

![rdt_v50_wrong2](\..\assets\rdt_v50_wrong2.png)

### RDT5.1 Reno

阻塞控制的基本思想是，每个终端都在检测到堵塞的时候减小自己的发包速率。

进行阻塞控制的基本实现，是在`rdt_send()`检查窗口，满则阻塞。

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L265
while (true) {
    if (nextSeq < sendBase + cwnd && !ReFlag) {
        // RDT4.2 送入发送窗口 ...
        // RDT4.3 动态RTT ...
        // RDT4.2 窗口头堵塞，准备重传 ...
        break;
```

触发阻塞控制状态转换的逻辑有：

- `ReTranTask.run()`中：

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L34
System.out.println("{S}[SS] congestion!");
Stat = CongStat.SS;
ReFlag = true; // 准备GBN
ssthresh = cwnd / 2;
cwnd = 1;
```

- `recv()`中：

```java
// /src/com/ouc/tcp/test/TCP_Sender.java#L221
if (cwnd >= ssthresh) { // CA: 加性增
    Stat = CongStat.CA;
    System.out.println("{S}[CA] addictive: " + ssthresh);
    cwnd += 1;
} else {// SS: 指数增
    System.out.println("{S}[SS] exponential : " + ssthresh);
    cwnd *= 2;
}
```

##### Log

慢增长：

<img src="\..\assets\rdt_v51_SS.png" alt="rdt_v51_SS" style="zoom:67%;" />

加性增：

<img src="\..\assets\rdt_v51_CA.png" alt="rdt_v51_CA" style="zoom:67%;" />

乘性减：

<img src="\..\assets\rdt_v51_CA2.png" alt="rdt_v51_CA2" style="zoom:67%;" />

由于是本机测试，未出现RTO超时的情况。

### 重大问题

#### 发送窗口和并发编程 | 花费时间：>4 hours

如何正确处理窗口的互斥访问是一个难点。

对于接收方来说，可以根据接收事件来驱动窗口的更新，故相对不涉及并发编程；
而对于发送方，既要及时响应上层调用，又要持续监听ACK，以实现重传。故发送窗口的维护至少会同时出现两个线程：(infinity window版本)

- 主循环`mainloop()`：不断遍历窗口，删除有序已确认部分，触发重传
- 监听ACK事件`recv()`：修改窗口项的状态，也需要找到对应窗口

如果遍历和修改同时发生在一个窗口项上，就会触发`ConcurrentModificationException`异常。

为了正确处理并发，可以用`synchronized`关键字（也就是锁）将要修改的窗口项保护起来，或者使用`CopyonWrite`数组，然而使用效果并不好。

**解决方法：**

最后注意到，由于RTT较短，我们也可以消灭主循环，全部让ACK事件来推动窗口，这样窗口的维护由两个函数触发：(RDT4.2最终版本)

- 应用层接口调用`rdt_send()`：发包，加入窗口
- 监听ACK事件`recv()`：修改对应项状态，刷新窗口

由于发送事件必然比对应的ACK事件早，故可以彻底解决并发问题。（解决并发问题的方法就是不并发）


#### 重传包错位和数组索引 | 花费时间：>3 hours

TCP初版对单一计时器的实现是，只重传第一个未应答包。问题是，用什么找到待重传的那个包？

首先，排除传数组索引的方法，因为窗口在不断变化，索引值是不可靠的。此处点名`SubList()`方法，不要在涉及修改数组的情景下使用这个方法，害人不浅。

其次，使用传引用时，找到的重传包和实际发送的重传包总是错位。

最后，笔者一怒之下决定传包序号+遍历查找。然而还是错位。

几经debug，才发现窗口里的所有项都总是指向最新的包，👴傻了。
这时候才发现构造方法里`TCP_PACKET`成员的一直是引用，而`TCP_PACKET`实例一直只有一个。即使用`final`修饰引用，引用指向的对象还是可以变。

**解决方法：**

使用构造窗口项对象时使用`clone()`方法。

### 实验系统建议

- 建议更新GUI版本，log可读性太差，且控制台输出过于繁多，容易干扰个人的调试。
- 建议添加远程测试服务器，本机测试代码可以说省略了网络波动带来的影响。
- 建议更新不定长数据包，把字节序号计算作为加分项。
- 下次还填非常简单。
