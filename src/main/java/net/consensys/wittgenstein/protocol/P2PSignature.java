package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.P2PNetwork;
import net.consensys.wittgenstein.core.P2PNode;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * A p2p protocol for BLS signature aggregation.
 * <p>
 * A node:
 * Sends its states to all its direct peers whenever it changes
 * Keeps the list of the states of its direct peers
 * Sends, every x milliseconds, to one of its peers a set of missing signatures
 * Runs in parallel a task to validate the signatures sets it has received.
 */
@SuppressWarnings("WeakerAccess")
public class P2PSignature {
    /**
     * The nuumber of nodes in the network
     */
    final int nodeCount;

    /**
     * The number of signatures to reach to finish the protocol.
     */
    final int threshold;

    /**
     * The typical number of peers a peer has. It can be less (but at least 3) or more.
     */
    final int connectionCount;

    /**
     * The time it takes to do a pairing for a node.
     */
    final int pairingTime;

    /**
     * The protocol sends a set of sigs every 'sigsSendPeriod' milliseconds
     */
    final int sigsSendPeriod;

    /**
     * @see P2PSigNode#sendSigs for the two strategies.
     */
    final boolean doubleAggregateStrategy;

    final P2PNetwork network;
    final Node.NodeBuilder nb;

    public P2PSignature(int nodeCount, int threshold, int connectionCount, int pairingTime, int sigsSendPeriod, boolean doubleAggregateStrategy) {
        this.nodeCount = nodeCount;
        this.threshold = threshold;
        this.connectionCount = connectionCount;
        this.pairingTime = pairingTime;
        this.sigsSendPeriod = sigsSendPeriod;
        this.doubleAggregateStrategy = doubleAggregateStrategy;

        this.network = new P2PNetwork(connectionCount);
        this.nb = new Node.NodeBuilderWithPosition(network.rd);
    }

    static class State extends Network.MessageContent<P2PSigNode> {
        final BitSet desc;
        final P2PSigNode who;

        public State(@NotNull P2PSigNode who) {
            this.desc = (BitSet) who.verifiedSignatures.clone();
            this.who = who;
        }

        @Override
        public int size() {
            return desc.size() / 8;
        }

        @Override
        public void action(@NotNull P2PSigNode from, @NotNull P2PSigNode to) {
            to.onPeerState(this);
        }
    }

    static class SendSigs extends Network.MessageContent<P2PSigNode> {
        final BitSet sigs;

        public SendSigs(@NotNull BitSet sigs) {
            this.sigs = sigs;
        }

        @Override
        public int size() {
            return sigs.cardinality() * 48;
        }

        @Override
        public void action(@NotNull P2PSigNode from, @NotNull P2PSigNode to) {
            to.onNewSig(sigs);
        }
    }

    public class P2PSigNode extends P2PNode {
        final BitSet verifiedSignatures = new BitSet(nodeCount);
        final Set<BitSet> toVerify = new HashSet<>();
        final Map<Integer, State> peersState = new HashMap<>();

        boolean done = false;
        long doneAt = 0;

        P2PSigNode() {
            super(nb);
            verifiedSignatures.set(nodeId, true);
        }

        /**
         * Asynchronous, so when we receive a state it can be an old one.
         */
        void onPeerState(@NotNull State state) {
            int newCard = state.desc.cardinality();
            State old = peersState.get(state.who.nodeId);

            if (newCard < threshold && (old == null || old.desc.cardinality() < newCard)) {
                peersState.put(state.who.nodeId, state);
            }
        }

        /**
         * If the state has changed we send a message to all.
         * If we're done, we updates all our peers.
         */
        void updateVerifiedSignatures(@NotNull BitSet sigs) {
            int oldCard = verifiedSignatures.cardinality();
            verifiedSignatures.or(sigs);
            int newCard = verifiedSignatures.cardinality();

            if (newCard > oldCard) {
                sendStateToPeers();

                if (!done && verifiedSignatures.cardinality() >= threshold) {
                    doneAt = network.time;
                    done = true;
                    while (!peersState.isEmpty()) {
                        sendSigs();
                    }
                }
            }
        }

        void sendStateToPeers() {
            State s = new State(this);
            network.send(s, network.time + 1, this, peers);
        }

        /**
         * Nothing much to do when we receive a sig set: we just add it to our
         * toVerify list.
         */
        void onNewSig(@NotNull BitSet sigs) {
            toVerify.add(sigs);
        }

        /**
         * We select a peer which needs some signatures we have.
         * We also remove it from out list once we sent it a signature set.
         */
        void sendSigs() {
            State found = null;
            BitSet toSend = null;
            Iterator<State> it = peersState.values().iterator();
            while (it.hasNext() && found == null) {
                State cur = it.next();
                toSend = (BitSet) cur.desc.clone();
                toSend.flip(0, nodeCount);
                toSend.and(verifiedSignatures);
                int v1 = toSend.cardinality();

                if (v1 > 0) {
                    found = cur;
                    it.remove();
                }
            }

            if (found != null) {
                SendSigs ss = new SendSigs(toSend);
                network.send(ss, delayToSend(ss.sigs), this, Collections.singleton(found.who));
            }
        }

        /**
         * We add a small delay to take into account the message size. This should likely
         * be moved to the framework.
         */
        int delayToSend(BitSet sigs) {
            return network.time + 1 + sigs.cardinality() / 100;
        }


        public void checkSigs() {
            if (doubleAggregateStrategy) {
                checkSigs2();
            } else {
                checkSigs1();
            }
        }


        /**
         * Strategy 1: we select the set of signatures which contains the most
         * new signatures. As we send a message to all our peers each time our
         * state change we send more messages with this strategy.
         */
        protected void checkSigs1() {
            BitSet best = null;
            int bestV = 0;
            Iterator<BitSet> it = toVerify.iterator();
            while (it.hasNext()) {
                BitSet o1 = it.next();
                BitSet oo1 = ((BitSet) o1.clone());
                oo1.andNot(verifiedSignatures);
                int v1 = oo1.cardinality();

                if (v1 == 0) {
                    it.remove();
                } else {
                    if (v1 > bestV) {
                        bestV = v1;
                        best = o1;
                    }
                }
            }

            if (best != null) {
                toVerify.remove(best);
                final BitSet tBest = best;
                network.registerTask(() -> P2PSigNode.this.updateVerifiedSignatures(tBest),
                        network.time + pairingTime, P2PSigNode.this);
            }
        }

        /**
         * Strategy 2: we aggregate all signatures together
         */
        protected void checkSigs2() {
            BitSet agg = null;
            for (BitSet o1 : toVerify) {
                if (agg == null) {
                    agg = o1;
                } else {
                    agg.or(o1);
                }
            }
            toVerify.clear();

            if (agg != null) {
                BitSet oo1 = ((BitSet) agg.clone());
                oo1.andNot(verifiedSignatures);

                if (oo1.cardinality() > 0) {
                    // There is at least one signature we don't have yet
                    final BitSet tBest = agg;
                    network.registerTask(() -> P2PSigNode.this.updateVerifiedSignatures(tBest),
                            network.time + pairingTime, P2PSigNode.this);
                }
            }
        }


        @Override
        public String toString() {
            return "P2PSigNode{" +
                    "nodeId=" + nodeId +
                    ", doneAt=" + doneAt +
                    ", sigs=" + verifiedSignatures.cardinality() +
                    ", msgReceived=" + msgReceived +
                    ", msgSent=" + msgSent +
                    ", KBytesSent=" + bytesSent / 1024 +
                    ", KBytesReceived=" + bytesReceived / 1024 +
                    '}';
        }
    }

    P2PSigNode init() {
        P2PSigNode last = null;
        for (int i = 0; i < nodeCount; i++) {
            final P2PSigNode n = new P2PSigNode();
            last = n;
            network.addNode(n);
            network.registerTask(n::sendStateToPeers, 1, n);
            //network.registerPeriodicTask(n::sendSigs, 1, 20, n, () -> !n.done);
            network.registerConditionalTask(n::sendSigs, 1, sigsSendPeriod, n, () -> !(n.peersState.isEmpty()), () -> !n.done);
            network.registerConditionalTask(n::checkSigs, 1, pairingTime, n, () -> !n.toVerify.isEmpty(), () -> !n.done);
            // network.registerPeriodicTask(n::checkSigs, 1, pairingTime, n, () -> !n.done);
        }

        network.setPeers();

        return last;
    }

// P2PSigNode{nodeId=999, doneAt=616, sigs=541, msgReceived=591, msgSent=284, KBytesSent=1008, KBytesReceived=1098}

    public static void main(String... args) {
        int[] distribProp = {1, 33, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
        int[] distribVal = {12, 15, 19, 32, 35, 37, 40, 42, 45, 87, 155, 160, 185, 297, 1200};

        P2PSignature p2ps = new P2PSignature(1000, 501,
                25, 3, 20, true);
        p2ps.network.setNetworkLatency(distribProp, distribVal);
        //p2ps.network.removeNetworkLatency();
        P2PSigNode observer = p2ps.init();
        p2ps.network.run(5);
        System.out.println(observer);
    }

}
