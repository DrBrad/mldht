/*
 *    This file is part of mlDHT.
 * 
 *    mlDHT is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 2 of the License, or
 *    (at your option) any later version.
 * 
 *    mlDHT is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 * 
 *    You should have received a copy of the GNU General Public License
 *    along with mlDHT.  If not, see <http://www.gnu.org/licenses/>.
 */
package lbms.plugins.mldht.kad.tasks;


import java.util.Set;
import java.util.stream.Collectors;

import lbms.plugins.mldht.kad.DHT.DHTtype;
import lbms.plugins.mldht.kad.DHTConstants;
import lbms.plugins.mldht.kad.KBucketEntry;
import lbms.plugins.mldht.kad.KClosestNodesSearch;
import lbms.plugins.mldht.kad.Key;
import lbms.plugins.mldht.kad.Node;
import lbms.plugins.mldht.kad.NodeList;
import lbms.plugins.mldht.kad.RPCCall;
import lbms.plugins.mldht.kad.RPCServer;
import lbms.plugins.mldht.kad.messages.FindNodeRequest;
import lbms.plugins.mldht.kad.messages.FindNodeResponse;
import lbms.plugins.mldht.kad.messages.MessageBase;
import lbms.plugins.mldht.kad.messages.MessageBase.Method;
import lbms.plugins.mldht.kad.messages.MessageBase.Type;
import lbms.plugins.mldht.kad.utils.AddressUtils;

/**
 * @author Damokles
 *
 */
public class NodeLookup extends IteratingTask {
	private int						validReponsesSinceLastClosestSetModification;

	private boolean forBootstrap = false;
	
	public NodeLookup (Key node_id, RPCServer rpc, Node node, boolean isBootstrap) {
		super(node_id, rpc, node);
		forBootstrap = isBootstrap;
		addListener(t -> updatedPopulationEstimates());
	}
	
	@Override
	void update () {
		for(;;) {
			// while(!todo.isEmpty() && canDoRequest() && !isClosestSetStable() && !nextTodoUseless())
			RequestPermit p = checkFreeSlot();
			if(p == RequestPermit.NONE_ALLOWED)
				return;
			
			KBucketEntry e = todo.next().orElse(null);
			
			if(e == null)
				return;
			
			if(!new RequestCandidateEvaluator(this, closest, todo, e, inFlight.values()).goodForRequest(p))
				return;
				
			// send a findNode to the node
			FindNodeRequest fnr = new FindNodeRequest(targetKey);
			fnr.setWant4(rpc.getDHT().getType() == DHTtype.IPV4_DHT || rpc.getDHT().getSiblings().stream().anyMatch(sib -> sib.isRunning() && sib.getType() == DHTtype.IPV4_DHT && sib.getNode().getNumEntriesInRoutingTable() < DHTConstants.BOOTSTRAP_IF_LESS_THAN_X_PEERS));
			fnr.setWant6(rpc.getDHT().getType() == DHTtype.IPV6_DHT || rpc.getDHT().getSiblings().stream().anyMatch(sib -> sib.isRunning() && sib.getType() == DHTtype.IPV6_DHT && sib.getNode().getNumEntriesInRoutingTable() < DHTConstants.BOOTSTRAP_IF_LESS_THAN_X_PEERS));
			fnr.setDestination(e.getAddress());
			
			if(!rpcCall(fnr,e.getID(), (call) -> {
				long rtt = e.getRTT();
				rtt = rtt + rtt / 2; // *1.5 since this is the average and not the 90th percentile like the timeout filter
				if(rtt < DHTConstants.RPC_CALL_TIMEOUT_MAX && rtt < rpc.getTimeoutFilter().getStallTimeout())
					call.setExpectedRTT(rtt); // only set a node-specific timeout if it's better than what the server would apply anyway
				call.builtFromEntry(e);
				todo.addCall(call, e);
			})) {
				break;
			}
				
		}
	}
	
	@Override
	protected boolean isDone() {
		int waitingFor = getNumOutstandingRequests();
		KBucketEntry next = todo.next().orElse(null);
		
		if(waitingFor != 0)
			return false;
		
		if(next == null)
			return true;
		
		return new RequestCandidateEvaluator(this, closest, todo, next, inFlight.values()).terminationPrecondition();
	}

	@Override
	void callFinished (RPCCall c, MessageBase rsp) {

		// check the response and see if it is a good one
		if (rsp.getMethod() != Method.FIND_NODE || rsp.getType() != Type.RSP_MSG)
			return;
		
		KBucketEntry match = todo.acceptResponse(c);
		
		if(match == null)
			return;

		FindNodeResponse fnr = (FindNodeResponse) rsp;
		
		closest.insert(match);
		
		
		for (DHTtype type : DHTtype.values())
		{
			NodeList nodes = fnr.getNodes(type);
			if (nodes == null)
				continue;
			if (type == rpc.getDHT().getType()) {
				Set<KBucketEntry> entries = nodes.entries().filter(e -> !AddressUtils.isBogon(e.getAddress()) && !node.isLocalId(e.getID())).collect(Collectors.toSet());
				todo.addCandidates(match, entries);
			} else {
				rpc.getDHT().getSiblings().stream().filter(sib -> sib.getType() == type).forEach(sib -> {
					nodes.entries().forEach(e -> {
						sib.addDHTNode(e.getAddress().getAddress().getHostAddress(), e.getAddress().getPort());
					});
				});
			}
		}

	}

	@Override
	void callTimeout (RPCCall c) {

	}

	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.Task#start()
	 */
	@Override
	public
	void start () {

		// if we're bootstrapping start from the bucket that has the greatest possible distance from ourselves so we discover new things along the (longer) path
		Key knsTargetKey = forBootstrap ? targetKey.distance(Key.MAX_KEY) : targetKey;
		
		// delay the filling of the todo list until we actually start the task
		KClosestNodesSearch kns = new KClosestNodesSearch(knsTargetKey, 3 * DHTConstants.MAX_ENTRIES_PER_BUCKET, rpc.getDHT());
		kns.filter = KBucketEntry::eligibleForLocalLookup;
		kns.fill();
		todo.addCandidates(null, kns.getEntries());
		

		super.start();
	}

	private void updatedPopulationEstimates () {
		synchronized (closest)
		{
			rpc.getDHT().getEstimator().update(closest.ids().collect(Collectors.toSet()),targetKey);
		}
	}
}
