package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.Protocol;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;
import net.consensys.wittgenstein.tools.SanFerminHelper;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;


/**
 * San Fermin's protocol adapted to BLS signature aggregation.
 * <p>
 * San Fermin is a protocol for distributed aggregation of a large data set over a large set of
 * nodes. It imposes a special binomial tree structures on the communication patterns of the node so
 * that each node only needs to contact O(log(n)) other nodes to get the final aggregated result.
 * SanFerminNode{nodeId=1000000001, doneAt=4860, sigs=874, msgReceived=272, msgSent=275,
 * KBytesSent=13, KBytesReceived=13, outdatedSwaps=0}
 */
@SuppressWarnings("WeakerAccess")
public class SanFerminCappos implements Protocol {

  /**
   * The number of nodes in the network
   */
  final int nodeCount;

  /**
   * The time it takes to do a pairing for a node i.e. simulation of the most heavy computation
   */
  final int pairingTime;

  /**
   * Size of a BLS signature (can be aggregated or not)
   */
  final int signatureSize;


  /**
   * Do we print logging information from nodes or not
   */
  boolean verbose;

  /**
   * how many candidate do we try to reach at the same time for a given level
   */
  int candidateCount;

  /**
   * Threshold is the ratio of number of actual contributions vs number of expected contributions in
   * a given range level
   */
  int threshold;

  /**
   * timeout after which to pass on to the next level in ms
   */
  int timeout;

  public List<SanFerminNode> getAllNodes() {
    return allNodes;
  }

  /**
   * allNodes represent the full list of nodes present in the system. NOTE: This assumption that a
   * node gets access to the full list can be dismissed, as they do in late sections in the paper.
   * For a first version and sake of simplicity, full knowledge of the peers is assumed here. A
   * partial knowledge graph can be used, where nodes will send requests to unknown ID "hoping" some
   * unknown peers yet can answer. This technique works because it uses Pastry ID allocation
   * mechanism. Here no underlying DHT or p2p special structure is assumed.
   */
  public List<SanFerminNode> allNodes;

  public List<SanFerminNode> finishedNodes;


  public SanFerminCappos(int nodeCount, int threshold, int pairingTime, int signatureSize,
      int timeout, int candidateCount) {
    this.nodeCount = nodeCount;
    this.pairingTime = pairingTime;
    this.signatureSize = signatureSize;
    this.candidateCount = candidateCount;
    this.threshold = threshold;
    this.timeout = timeout;

    this.network = new Network<>();
    this.nb = new Node.NodeBuilderWithRandomPosition();

    this.allNodes = new ArrayList<>(nodeCount);
    for (int i = 0; i < nodeCount; i++) {
      final SanFerminNode n = new SanFerminNode(this.nb);
      this.allNodes.add(n);
      this.network.addNode(n);
    }

    // compute candidate set once all peers have been created
    for (SanFerminNode n : allNodes)
      n.helper = new SanFerminHelper<>(n, allNodes, this.network.rd);


    finishedNodes = new ArrayList<>();
  }

  final Network<SanFerminNode> network;
  final Node.NodeBuilder nb;

  @Override
  public Network<SanFerminNode> network() {
    return network;
  }

  @Override
  public void init() {

  }



  /**
   * init makes each node starts swapping with each other when the network starts
   */
  public void StartAll() {
    for (SanFerminNode n : allNodes)
      network.registerTask(n::goNextLevel, 1, n);
  }

  public SanFerminCappos copy() {
    return new SanFerminCappos(nodeCount, threshold, pairingTime, signatureSize, timeout,
        candidateCount);
  }

  /**
   * SanFerminNode is a node that carefully selects the peers he needs to contact to get the final
   * aggregated result
   */
  public class SanFerminNode extends Node {
    /**
     * The node's id in binary string form
     */
    public final String binaryId;

    private SanFerminHelper<SanFerminNode> helper;

    /**
     * This node needs to exchange with another node having a current common prefix length of
     * currentPrefixLength. A node starts by the highest possible prefix length and works towards a
     * prefix length of 0.
     */
    public int currentPrefixLength;

    /**
     * Set of received signatures at each level.
     */
    HashMap<Integer, List<Integer>> signatureCache;
    /**
     * isSwapping indicates whether we are in a state where we can swap at the current level or not.
     * This is acting as a Lock, since between the time we receive a valid swap and the time we
     * aggregate it, we need to verify it. In the meantime, another valid swap can come and thus we
     * would end up swapping twice at a given level.
     */
    boolean isSwapping;

    /**
     * Integer field that simulate an aggregated signature badly. It keeps increasing as the node do
     * more swaps. It assumes each node has the value "1" and the aggregation operation is the
     * addition.
     */
    int aggValue;

    /**
     * time when threshold of signature is reached
     */
    long thresholdAt;
    /**
     * have we reached the threshold or not
     */
    boolean thresholdDone;

    /**
     * Are we done yet or not
     */
    boolean done;


    public SanFerminNode(NodeBuilder nb) {
      super(network.rd, nb);
      this.binaryId = SanFerminHelper.toBinaryID(this, nodeCount);
      this.done = false;
      this.thresholdDone = false;
      this.aggValue = 1;
      this.isSwapping = false;
      // node should start at n-1 with N = 2^n
      // this counter gets decreased with `goNextLevel`.
      this.currentPrefixLength = MoreMath.log2(nodeCount);
      this.signatureCache = new HashMap<>();
    }

    /**
     * onSwap checks if it is a swap a the current level and from a candidate node. If it is, it
     * reply with his own swap, aggregates the value and move on to the next level. If it is not at
     * the current level, it replies with a cached value if it has, or save the value for later if
     * valid. If it is not from a candidate set, then it drops the message.
     */
    public void onSwap(SanFerminNode from, Swap swap) {
      boolean wantReply = swap.wantReply;
      if (done || swap.level != this.currentPrefixLength) {
        boolean isValueCached = this.signatureCache.containsKey(swap.level);
        if (wantReply && isValueCached) {
          print(
              "sending back CACHED signature at level " + swap.level + " to node " + from.binaryId);
          this.sendSwap(Collections.singletonList(from), swap.level,
              this.getBestCachedSig(swap.level), false);
        } else {
          // it's a value we might want to keep for later!
          boolean isCandidate = this.helper.isCandidate(from, swap.level);
          boolean isValidSig = true; // as always :)
          if (isCandidate && isValidSig) {
            // it is a good request we can save for later!
            this.putCachedSig(swap.level, swap.aggValue);
          }
        }
        return;
      }

      if (wantReply) {
        this.sendSwap(Collections.singletonList(from), swap.level,
            this.totalNumberOfSigs(swap.level), false);
      }


      // accept if it is a valid swap !
      boolean goodLevel = swap.level == currentPrefixLength;
      boolean isCandidate = this.helper.isCandidate(from, currentPrefixLength);
      boolean isValidSig = true; // as always :)
      if (isCandidate && goodLevel && isValidSig) {
        if (!isSwapping)
          transition(" received valid SWAP ", from.binaryId, swap.level, swap.aggValue);
      } else {
        print(" received  INVALID Swap" + "from " + from.binaryId + " at level " + swap.level);
        print("   ---> " + isValidSig + " - " + goodLevel + " - " + isCandidate);
      }
    }

    /**
     * tryNextNodes simply picks the next eligible candidate from the list and send a swap request
     * to it. It attaches a timeout to the request. If no SwapReply has been received before
     * timeout, tryNextNodes() will be called again.
     */
    private void tryNextNodes(List<SanFerminNode> candidates) {
      if (candidates.size() == 0) {
        // when malicious actors are introduced or some nodes are
        // failing this case can happen. In that case, the node
        // should go to the next level since he has nothing better to
        // do. The final aggregated signature will miss this level
        // but it may still reach the threshold and can be completed
        // later on, through the help of a bit field.
        print(" is OUT (no more " + "nodes to pick)");
        return;
      }
      candidates.stream().filter(n -> !helper.isCandidate(n, currentPrefixLength)).forEach(n -> {
        System.out.println("currentPrefixlength=" + currentPrefixLength + " vs helper.currentLevel="
            + helper.currentLevel);
        throw new IllegalStateException();
      });

      print(" send Swaps to " + String.join(" - ",
          candidates.stream().map(n -> n.binaryId).collect(Collectors.toList())));
      this.sendSwap(candidates, this.currentPrefixLength,
          this.totalNumberOfSigs(this.currentPrefixLength + 1), true);

      int currLevel = this.currentPrefixLength;
      network.registerTask(() -> {
        // If we are still waiting on an answer for this level, we
        // try a new one.
        if (!SanFerminNode.this.done && SanFerminNode.this.currentPrefixLength == currLevel) {
          print("TIMEOUT of SwapRequest at level " + currLevel);
          // that means we haven't got a successful reply for that
          // level so we try another node
          List<SanFerminNode> nextNodes =
              this.helper.pickNextNodes(this.currentPrefixLength, candidateCount);
          tryNextNodes(nextNodes);
        }
      }, network.time + timeout, SanFerminNode.this);

    }


    /**
     * goNextLevel reduces the required length of common prefix of one, computes the new set of
     * potential nodes and sends an invitation to the "next" one. There are many ways to select the
     * order of which node to choose. In case the number of nodes is 2^n, there is a 1-to-1 mapping
     * that exists so that each node has exactly one unique node to swap with. In case it's not,
     * there are going to be some nodes who will be unable to continue the protocol since the
     * "chosen" node will likely already have swapped. See `pickNextNode` for more information.
     */
    private void goNextLevel() {

      if (done) {
        return;
      }

      boolean enoughSigs = totalNumberOfSigs(this.currentPrefixLength) >= threshold;
      boolean noMoreSwap = this.currentPrefixLength == 0;

      if (enoughSigs && !thresholdDone) {
        print(" --- THRESHOLD REACHED --- ");
        thresholdDone = true;
        thresholdAt = network.time + pairingTime * 2;
      }

      if (noMoreSwap && !done) {
        print(" --- FINISHED ---- protocol");
        doneAt = network.time + pairingTime * 2;
        finishedNodes.add(this);
        done = true;
        return;
      }
      this.currentPrefixLength--;
      this.isSwapping = false;

      if (signatureCache.containsKey(currentPrefixLength)) {
        print(" FUTURe value at new level" + currentPrefixLength + " "
            + "saved. Moving on directly !");
        // directly go to the next level !
        goNextLevel();
        return;
      }
      List<SanFerminNode> newNodes = this.helper.pickNextNodes(currentPrefixLength, candidateCount);
      this.tryNextNodes(newNodes);
    }

    private void sendSwap(List<SanFerminNode> nodes, int level, int value, boolean wantReply) {
      Swap r = new Swap(level, value, wantReply);
      network.send(r, SanFerminNode.this, nodes);
    }

    public int totalNumberOfSigs(int level) {
      return this.signatureCache
          .entrySet()
          .stream()
          .filter(entry -> entry.getKey() >= level)
          .map(entry -> entry.getValue())
          .map(list -> list.stream().max(Integer::max).get())
          .reduce(0, Integer::sum) + 1; // +1 for own sig
    }

    /**
     * Transition prevents any more aggregation at this level, and launch the "verification routine"
     * and move on to the next level. The first three parameters are only here for logging purposes.
     */
    private void transition(String type, String fromId, int level, int toAggregate) {
      this.isSwapping = true;
      network.registerTask(() -> {
        print(" received " + type + " lvl=" + level + " from " + fromId);
        this.putCachedSig(level, toAggregate);
        this.goNextLevel();
      }, network.time + pairingTime, this);
    }

    private int getBestCachedSig(int level) {
      List<Integer> cached = this.signatureCache.getOrDefault(level, new ArrayList<>());
      int max = cached.stream().reduce(Integer::max).get();
      return max;
    }

    private void putCachedSig(int level, int value) {
      List<Integer> list = this.signatureCache.getOrDefault(level, new ArrayList<>());
      list.add(value);
      this.signatureCache.put(level, list);
      boolean enoughSigs = totalNumberOfSigs(this.currentPrefixLength) >= threshold;

      if (enoughSigs && !thresholdDone) {
        print(" --- THRESHOLD REACHED --- ");
        thresholdDone = true;
        thresholdAt = network.time + pairingTime * 2;
      }
    }

    public long getThresholdAt() {
      return thresholdAt;
    }

    public long getDoneAt() {
      return doneAt;
    }

    /**
     * simple helper method to print node info + message
     */
    private void print(String s) {
      if (verbose)
        System.out.println("t=" + network.time + ", id=" + this.binaryId + ", lvl="
            + this.currentPrefixLength + ", sent=" + this.msgSent + " -> " + s);
    }

    @Override
    public String toString() {
      return "SanFerminNode{" + "nodeId=" + binaryId + ", thresholdAt=" + thresholdAt + ", doneAt="
          + doneAt + ", sigs=" + totalNumberOfSigs(-1) + ", msgReceived=" + msgReceived + ", "
          + "msgSent=" + msgSent + ", KBytesSent=" + bytesSent / 1024 + ", KBytesReceived="
          + bytesReceived / 1024 + '}';
    }
  }


  class Swap extends Network.Message<SanFerminNode> {

    boolean wantReply; // indicate that the other needs a reply to this
    // Swap
    final int level;
    int aggValue; // see Reply.aggValue
    // String data -- no need to specify it, but only in the size() method


    public Swap(int level, int aggValue, boolean reply) {
      this.level = level;
      this.wantReply = reply;
      this.aggValue = aggValue;
    }

    @Override
    public void action(SanFerminNode from, SanFerminNode to) {
      to.onSwap(from, this);
    }

    @Override
    public int size() {
      // uint32 + sig size
      return 4 + signatureSize;
    }
  }

  public static void sigsPerTime() {
    NetworkLatency.NetworkLatencyByDistance nl = new NetworkLatency.NetworkLatencyByDistance();
    int nodeCt = 32768 / 2;

    SanFerminCappos ps1 = new SanFerminCappos(nodeCt, nodeCt / 2, 2, 48, 150, 50);

    ps1.network.setNetworkLatency(nl);

    Graph graph = new Graph("number of sig per time", "time in ms", "sig count");
    Graph.Series series1min = new Graph.Series("sig count - worse node");
    Graph.Series series1max = new Graph.Series("sig count - best node");
    Graph.Series series1avg = new Graph.Series("sig count - avg");
    graph.addSerie(series1min);
    graph.addSerie(series1max);
    graph.addSerie(series1avg);

    ps1.StartAll();

    StatsHelper.SimpleStats s;
    final long limit = 6000;
    do {
      ps1.network.runMs(10);
      s = StatsHelper.getStatsOn(ps1.allNodes, n -> {
        SanFerminNode sfn = ((SanFerminNode) n);
        return sfn.totalNumberOfSigs(-1);
      });
      series1min.addLine(new Graph.ReportLine(ps1.network.time, s.min));
      series1max.addLine(new Graph.ReportLine(ps1.network.time, s.max));
      series1avg.addLine(new Graph.ReportLine(ps1.network.time, s.avg));
    } while (ps1.network.time < limit);

    try {
      graph.save(new File("/tmp/graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }

    System.out.println("bytes sent: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getBytesSent));
    System.out
        .println("bytes rcvd: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getBytesReceived));
    System.out.println("msg sent: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getMsgSent));
    System.out.println("msg rcvd: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getMsgReceived));
    System.out.println("done at: " + StatsHelper.getStatsOn(ps1.network.allNodes, n -> {
      long val = n.getDoneAt();
      return val == 0 ? limit : val;
    }));
  }

  public static void main(String... args) {

    if (true)
      sigsPerTime();


    int[] distribProp = {1, 33, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
    int[] distribVal = {12, 15, 19, 32, 35, 37, 40, 42, 45, 87, 155, 160, 185, 297, 1200};
    for (int i = 0; i < distribVal.length; i++)
      distribVal[i] += 50; // more or less the latency we had before the refactoring

    SanFerminCappos p2ps;


    p2ps = new SanFerminCappos(32, 4, 4, 48, 100, 3);
    //p2ps = new SanFerminCappos(1024, 800, 4, 48, 100, 50);
    //p2ps = new SanFerminCappos(16384, 8192, 4, 48, 100, 50);
    p2ps.verbose = true;
    p2ps.network.setNetworkLatency(distribProp, distribVal).setMsgDiscardTime(1000);
    //p2ps.network.removeNetworkLatency();

    p2ps.StartAll();
    p2ps.network.run(30);

    int max = 10;
    // print results first reached threshold
    System.out.println(" --- First reaching threshold ---");
    p2ps.finishedNodes
        .stream()
        .sorted(Comparator.comparingLong(SanFerminNode::getThresholdAt))
        .limit(max)
        .forEach(System.out::println);

    System.out.println(" --- First reaching full sig ---");
    p2ps.finishedNodes
        .stream()
        .sorted(Comparator.comparingLong(SanFerminNode::getDoneAt))
        .limit(max)
        .forEach(System.out::println);


    System.out.println(" --- Unfinished nodes ---");
    p2ps.allNodes.stream().filter(n -> !n.done).limit(max).forEach(System.out::println);
    p2ps.network.printNetworkLatency();
  }
}

/**
 * --- First reaching threshold --- SanFerminNode{nodeId=01111001011111, thresholdAt=877,
 * doneAt=1016, sigs=16384, msgReceived=2099, msgSent=2148, KBytesSent=109, KBytesReceived=106}
 * SanFerminNode{nodeId=01011001000110, thresholdAt=877, doneAt=1030, sigs=16384, msgReceived=2141,
 * msgSent=2190, KBytesSent=111, KBytesReceived=108} SanFerminNode{nodeId=01101000101100,
 * thresholdAt=879, doneAt=1073, sigs=16384, msgReceived=2104, msgSent=2164, KBytesSent=109,
 * KBytesReceived=106} SanFerminNode{nodeId=00010001100100, thresholdAt=880, doneAt=947, sigs=16384,
 * msgReceived=2332, msgSent=2386, KBytesSent=121, KBytesReceived=118}
 * SanFerminNode{nodeId=00010001001000, thresholdAt=880, doneAt=947, sigs=16384, msgReceived=2914,
 * msgSent=2971, KBytesSent=150, KBytesReceived=147} SanFerminNode{nodeId=00100001100100,
 * thresholdAt=880, doneAt=947, sigs=16384, msgReceived=3135, msgSent=3185, KBytesSent=161,
 * KBytesReceived=159} SanFerminNode{nodeId=00110000111000, thresholdAt=880, doneAt=947, sigs=16384,
 * msgReceived=2979, msgSent=3030, KBytesSent=153, KBytesReceived=151}
 * SanFerminNode{nodeId=00100001100011, thresholdAt=880, doneAt=947, sigs=16384, msgReceived=4460,
 * msgSent=4508, KBytesSent=228, KBytesReceived=226} SanFerminNode{nodeId=00100001010000,
 * thresholdAt=880, doneAt=947, sigs=16384, msgReceived=4366, msgSent=4409, KBytesSent=223,
 * KBytesReceived=221} SanFerminNode{nodeId=00000001100100, thresholdAt=880, doneAt=947, sigs=16384,
 * msgReceived=7963, msgSent=8010, KBytesSent=406, KBytesReceived=404} --- First reaching full sig
 * --- SanFerminNode{nodeId=10001001011011, thresholdAt=945, doneAt=945, sigs=16384,
 * msgReceived=2017, msgSent=2061, KBytesSent=104, KBytesReceived=102}
 * SanFerminNode{nodeId=00101000101010, thresholdAt=946, doneAt=946, sigs=16384, msgReceived=2175,
 * msgSent=2225, KBytesSent=112, KBytesReceived=110} SanFerminNode{nodeId=00010001100100,
 * thresholdAt=880, doneAt=947, sigs=16384, msgReceived=2332, msgSent=2386, KBytesSent=121,
 * KBytesReceived=118} SanFerminNode{nodeId=00010000011010, thresholdAt=943, doneAt=947, sigs=16384,
 * msgReceived=3006, msgSent=3071, KBytesSent=155, KBytesReceived=152}
 * SanFerminNode{nodeId=00010001001000, thresholdAt=880, doneAt=947, sigs=16384, msgReceived=2914,
 * msgSent=2971, KBytesSent=150, KBytesReceived=147} SanFerminNode{nodeId=00010001010100,
 * thresholdAt=943, doneAt=947, sigs=16384, msgReceived=3023, msgSent=3069, KBytesSent=155,
 * KBytesReceived=153} SanFerminNode{nodeId=00101000100011, thresholdAt=943, doneAt=947, sigs=16384,
 * msgReceived=2159, msgSent=2227, KBytesSent=113, KBytesReceived=109}
 * SanFerminNode{nodeId=00100001100100, thresholdAt=880, doneAt=947, sigs=16384, msgReceived=3135,
 * msgSent=3185, KBytesSent=161, KBytesReceived=159} SanFerminNode{nodeId=00110000111000,
 * thresholdAt=880, doneAt=947, sigs=16384, msgReceived=2979, msgSent=3030, KBytesSent=153,
 * KBytesReceived=151} SanFerminNode{nodeId=00110001011110, thresholdAt=943, doneAt=947, sigs=16384,
 * msgReceived=2985, msgSent=3039, KBytesSent=154, KBytesReceived=151}
 */
