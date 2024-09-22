package me.cortex.voxy.client.core.rendering.hierachical2;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.section.AbstractSectionGeometryManager;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.ExpandingObjectAllocationList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.jellysquid.mods.sodium.client.util.MathUtil;

public class NodeManager2 {
    //Assumptions:
    // all nodes have children (i.e. all nodes have at least one child existence bit set at all times)
    // leaf nodes always contain geometry (empty geometry counts as geometry (it just doesnt take any memory to store))
    // All nodes except top nodes have parents

    //NOTE:
    // For the queue processing, will need a redirect node-value type
    //      since for inner node child resize gpu could take N frames to update

    public static final int NULL_GEOMETRY_ID = -1;
    public static final int EMPTY_GEOMETRY_ID = -2;

    public static final int NODE_ID_MSK = ((1<<24)-1);
    private static final int NODE_TYPE_MSK = 0b11<<30;
    private static final int NODE_TYPE_LEAF = 0b00<<30;
    private static final int NODE_TYPE_INNER = 0b01<<30;
    private static final int NODE_TYPE_REQUEST = 0b10<<30;

    private static final int REQUEST_TYPE_SINGLE = 0b0<<29;
    private static final int REQUEST_TYPE_CHILD = 0b1<<29;
    private static final int REQUEST_TYPE_MSK = 0b1<<29;

    //Single requests are basically _only_ generated by the insertion of top level nodes
    private final ExpandingObjectAllocationList<SingleNodeRequest> singleRequests = new ExpandingObjectAllocationList<>(SingleNodeRequest[]::new);
    private final ExpandingObjectAllocationList<NodeChildRequest> childRequests = new ExpandingObjectAllocationList<>(NodeChildRequest[]::new);
    private final IntOpenHashSet nodeUpdates = new IntOpenHashSet();
    private final AbstractSectionGeometryManager geometryManager;
    private final SectionUpdateRouter updateRouter;
    private final Long2IntOpenHashMap activeSectionMap = new Long2IntOpenHashMap();
    private final NodeStore nodeData;
    public final int maxNodeCount;
    public NodeManager2(int maxNodeCount, AbstractSectionGeometryManager geometryManager, SectionUpdateRouter updateRouter) {
        if (!MathUtil.isPowerOfTwo(maxNodeCount)) {
            throw new IllegalArgumentException("Max node count must be a power of 2");
        }
        if (maxNodeCount>(1<<24)) {
            throw new IllegalArgumentException("Max node count cannot exceed 2^24");
        }
        this.activeSectionMap.defaultReturnValue(-1);
        this.updateRouter = updateRouter;
        this.maxNodeCount = maxNodeCount;
        this.nodeData = new NodeStore(maxNodeCount);
        this.geometryManager = geometryManager;
    }

    public void insertTopLevelNode(long pos) {
        if (this.activeSectionMap.containsKey(pos)) {
            Logger.error("Tried inserting top level pos " + WorldEngine.pprintPos(pos) + " but it was in active map, discarding!");
            return;
        }

        var request = new SingleNodeRequest(pos);
        int id = this.singleRequests.put(request);
        this.updateRouter.watch(pos, WorldEngine.UPDATE_FLAGS);
        this.activeSectionMap.put(pos, id|NODE_TYPE_REQUEST|REQUEST_TYPE_SINGLE);
    }

    public void removeTopLevelNode(long pos) {
        int nodeId = this.activeSectionMap.get(pos);
        if (nodeId == -1) {
            Logger.error("Tried removing top level pos " + WorldEngine.pprintPos(pos) + " but it was not in active map, discarding!");
            return;
        }
        //TODO: assert is top level node
    }

    //==================================================================================================================

    public void processGeometryResult(BuiltSection sectionResult) {
        long pos = sectionResult.position;
        int nodeId = this.activeSectionMap.get(pos);
        if (nodeId == -1) {
            Logger.error("Got geometry update for pos " + WorldEngine.pprintPos(pos) + " but it was not in active map, discarding!");
            sectionResult.free();
            return;
        }

        if ((nodeId&NODE_TYPE_MSK)==NODE_TYPE_REQUEST) {
            //For a request
            if ((nodeId&REQUEST_TYPE_MSK)==REQUEST_TYPE_SINGLE) {
                var request = this.singleRequests.get(nodeId&NODE_ID_MSK);
                request.setMesh(this.uploadReplaceSection(request.getMesh(), sectionResult));

                //sectionResult has a cheeky childExistence field that we can use to set the request too, this is just
                // because processChildChange is only ever invoked when child existence changes, so we still need to
                // populate the request somehow, it will only set it if it hasnt been set before
                if (!request.hasChildExistenceSet()) {
                    request.setChildExistence(sectionResult.childExistence);
                }

                if (request.isSatisfied()) {
                    this.singleRequests.release(nodeId&NODE_ID_MSK);
                    this.finishRequest(request);
                }
            } else if ((nodeId&REQUEST_TYPE_MSK)==REQUEST_TYPE_CHILD) {
                var request = this.childRequests.get(nodeId&NODE_ID_MSK);
                int childId = getChildIdx(pos);
                request.setChildMesh(childId, this.uploadReplaceSection(request.getChildMesh(childId), sectionResult));
                if (!request.hasChildChildExistence(childId)) {
                    request.setChildChildExistence(childId, sectionResult.childExistence);
                }

                if (request.isSatisfied()) {
                    this.finishRequest(nodeId&NODE_ID_MSK, request);
                }
            } else {
                throw new IllegalStateException();
            }
        } else if ((nodeId&NODE_TYPE_MSK)==NODE_TYPE_INNER || (nodeId&NODE_TYPE_MSK)==NODE_TYPE_LEAF) {
            // Just doing a geometry update
            if (this.updateNodeGeometry(nodeId&NODE_ID_MSK, sectionResult) != 0) {
                this.nodeUpdates.add(nodeId&NODE_ID_MSK);
            }
        }
    }

    private int uploadReplaceSection(int meshId, BuiltSection section) {
        if (section.isEmpty()) {
            if (meshId != NULL_GEOMETRY_ID && meshId != EMPTY_GEOMETRY_ID) {
                this.geometryManager.removeSection(meshId);
            }
            section.free();
            return EMPTY_GEOMETRY_ID;
        }
        if (meshId != NULL_GEOMETRY_ID && meshId != EMPTY_GEOMETRY_ID) {
            return this.geometryManager.uploadReplaceSection(meshId, section);
        }
        return this.geometryManager.uploadSection(section);
    }

    private int updateNodeGeometry(int node, BuiltSection geometry) {
        int previousGeometry = this.nodeData.getNodeGeometry(node);
        int newGeometry = EMPTY_GEOMETRY_ID;
        if (previousGeometry != EMPTY_GEOMETRY_ID && previousGeometry != NULL_GEOMETRY_ID) {
            if (!geometry.isEmpty()) {
                newGeometry = this.geometryManager.uploadReplaceSection(previousGeometry, geometry);
            } else {
                this.geometryManager.removeSection(previousGeometry);
            }
        } else {
            if (!geometry.isEmpty()) {
                newGeometry = this.geometryManager.uploadSection(geometry);
            }
        }

        if (previousGeometry != newGeometry) {
            this.nodeData.setNodeGeometry(node, newGeometry);
        }
        if (previousGeometry == newGeometry) {
            return 0;//No change
        } else if (previousGeometry == EMPTY_GEOMETRY_ID || previousGeometry == NULL_GEOMETRY_ID) {
            return 1;//Became non-empty/non-null
        } else {
            return 2;//Became empty
        }
    }
    //==================================================================================================================

    public void processChildChange(long pos, byte childExistence) {
        int nodeId = this.activeSectionMap.get(pos);
        if (nodeId == -1) {
            Logger.error("Got child change for pos " + WorldEngine.pprintPos(pos) + " but it was not in active map, ignoring!");
            return;
        }


        if ((nodeId&NODE_TYPE_MSK)==NODE_TYPE_REQUEST) {
            //For a request
            if ((nodeId&REQUEST_TYPE_MSK)==REQUEST_TYPE_SINGLE) {
                var request = this.singleRequests.get(nodeId&NODE_ID_MSK);
                request.setChildExistence(childExistence);
                if (request.isSatisfied()) {
                    this.singleRequests.release(nodeId&NODE_ID_MSK);
                    this.finishRequest(request);
                }
            } else if ((nodeId&REQUEST_TYPE_MSK)==REQUEST_TYPE_CHILD) {
                var request = this.childRequests.get(nodeId&NODE_ID_MSK);
                request.setChildChildExistence(getChildIdx(pos), childExistence);
                if (request.isSatisfied()) {
                    this.finishRequest(nodeId&NODE_ID_MSK, request);
                }
            } else {
                throw new IllegalStateException();
            }
        } else if ((nodeId&NODE_TYPE_MSK)==NODE_TYPE_INNER) {
            //Very complex and painful operation

        } else if ((nodeId&NODE_TYPE_MSK)==NODE_TYPE_LEAF) {
            //Just need to update the child node data, nothing else
            this.nodeData.setNodeChildExistence(nodeId&NODE_ID_MSK, childExistence);
        }
    }

    //==================================================================================================================

    private void finishRequest(SingleNodeRequest request) {
        int id = this.nodeData.allocate();
        this.nodeData.setNodePosition(id, request.getPosition());
        this.nodeData.setNodeGeometry(id, request.getMesh());
        this.nodeData.setNodeChildExistence(id, request.getChildExistence());
        //TODO: this (or remove)
        //this.nodeData.setNodeType();
        this.activeSectionMap.put(request.getPosition(), id|NODE_TYPE_LEAF);//Assume that the result of any single request type is a leaf node
        this.nodeUpdates.add(id);
    }

    private void finishRequest(int requestId, NodeChildRequest request) {
        int parentNodeId = this.activeSectionMap.get(request.getPosition());
        if (parentNodeId == -1 || (parentNodeId&NODE_TYPE_MSK)==NODE_TYPE_REQUEST) {
            throw new IllegalStateException("CRITICAL BAD STATE!!! finishRequest tried to finish for a node that no longer exists in the map or has become a request type somehow?!!?!!" + WorldEngine.pprintPos(request.getPosition()) + " " + parentNodeId);
        }

        if ((parentNodeId&NODE_TYPE_MSK)==NODE_TYPE_LEAF) {
            int msk = Byte.toUnsignedInt(request.getMsk());
            int base = this.nodeData.allocate(Integer.bitCount(msk));
            int offset = -1;
            for (int childIdx = 0; childIdx < 8; childIdx++) {
                if ((msk&(1<<childIdx)) == 0) {
                    continue;
                }
                offset++;

                long childPos = makeChildPos(request.getPosition(), childIdx);
                int childNodeId = base+offset;
                //Fill in node
                this.nodeData.setNodePosition(childNodeId, childPos);
                this.nodeData.setNodeChildExistence(childNodeId, request.getChildChildExistence(childIdx));
                this.nodeData.setNodeGeometry(childNodeId, request.getChildMesh(childIdx));
                //Mark for update
                this.nodeUpdates.add(childNodeId);
                //Put in map
                int pid = this.activeSectionMap.put(childPos, childNodeId|NODE_TYPE_LEAF);
                if ((pid&NODE_TYPE_MSK) != NODE_TYPE_REQUEST) {
                    throw new IllegalStateException("Put node in map from request but type was not request: " + pid + " " + WorldEngine.pprintPos(childPos));
                }
            }
            //Free request
            this.childRequests.release(requestId);
            //Update the parent
            this.nodeData.setChildPtr(parentNodeId, base);
            this.nodeData.setChildPtrCount(parentNodeId, offset+1);
            this.nodeData.setNodeRequest(parentNodeId, 0);//TODO: create a better null request
            this.nodeData.unmarkRequestInFlight(parentNodeId);
            this.nodeUpdates.add(parentNodeId);
        } else if ((parentNodeId&NODE_TYPE_MSK)==NODE_TYPE_INNER) {
            System.err.println("TODO: FIXME FINISH: finishRequest NODE_TYPE_INNER");
        } else {
            throw new IllegalStateException();
        }
    }

    //==================================================================================================================
    public void processRequest(long pos) {
        int nodeId = this.activeSectionMap.get(pos);
        if (nodeId == -1) {
            Logger.error("Got request for pos " + WorldEngine.pprintPos(pos) + " but it was not in active map, ignoring!");
            return;
        }
        int nodeType = nodeId&NODE_TYPE_MSK;
        nodeId &= NODE_ID_MSK;
        if (nodeType == NODE_TYPE_REQUEST) {
            Logger.error("Tried processing request for pos: " + WorldEngine.pprintPos(pos) + " but its type was a request, ignoring!");
            return;
        } else if (nodeType != NODE_TYPE_LEAF && nodeType != NODE_TYPE_INNER ) {
            throw new IllegalStateException("Unknown node type: " + nodeType);
        }

        if (this.nodeData.isNodeRequestInFlight(nodeId)) {
            Logger.warn("Tried processing a node that already has a request in flight: " + nodeId + " pos: " + WorldEngine.pprintPos(pos) + " ignoring");
            return;
        }
        this.nodeData.markRequestInFlight(nodeId);

        if (nodeType == NODE_TYPE_LEAF) {
            //The hard one of processRequest, spin up a new request for the node
            this.makeLeafChildRequest(nodeId);

        } else {//nodeType == NODE_TYPE_INNER
            //TODO: assert that the node isnt already being watched for geometry, if it is, just spit out a warning? and ignore

            if (!this.updateRouter.watch(pos, WorldEngine.UPDATE_TYPE_BLOCK_BIT)) {
                //FIXME: i think this can occur accidently? when removing nodes or something creating leaf nodes
                // or other, the node might be wanted to be watched by gpu, but cpu already started watching it a few frames ago
                Logger.warn("Node: " + nodeId + " at pos: " + WorldEngine.pprintPos(pos) + " got update request, but geometry was already being watched");
            }
        }
    }

    private void makeLeafChildRequest(int nodeId) {
        long pos = this.nodeData.nodePosition(nodeId);
        byte childExistence = this.nodeData.getNodeChildExistence(nodeId);

        //Enqueue a leaf expansion request
        var request = new NodeChildRequest(pos);
        int requestId = this.childRequests.put(request);

        //Only request against the childExistence mask, since the guarantee is that if childExistence bit is not set then that child is guaranteed to be empty
        for (int i = 0; i < 8; i++) {
            if ((childExistence&(1<<i))==0) {
                //Dont watch or enqueue the child node cause it doesnt exist
                continue;
            }
            long childPos = makeChildPos(pos, i);
            request.addChildRequirement(i);

            //Insert all the children into the tracking map with the node id
            int pid = this.activeSectionMap.put(childPos, requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_CHILD);
            if (pid != -1) {
                throw new IllegalStateException("Leaf request creation failed to insert child into map as a mapping already existed for the node! pos: " + WorldEngine.pprintPos(childPos) + " id: " + pid);
            }

            //Watch and request the child node at the given position
            if (!this.updateRouter.watch(childPos, WorldEngine.UPDATE_FLAGS)) {
                throw new IllegalStateException("Failed to watch childPos");
            }
        }

        this.nodeData.setNodeRequest(nodeId, requestId);
    }

    //==================================================================================================================
    public boolean writeChanges(GlBuffer nodeBuffer) {
        //TODO: use like compute based copy system or something
        // since microcopies are bad
        if (this.nodeUpdates.isEmpty()) {
            return false;
        }
        for (int i : this.nodeUpdates) {
            this.nodeData.writeNode(UploadStream.INSTANCE.upload(nodeBuffer, i*16L, 16L), i);
        }
        this.nodeUpdates.clear();
        return true;
    }



    //==================================================================================================================
    private static int getChildIdx(long pos) {
        int x = WorldEngine.getX(pos);
        int y = WorldEngine.getY(pos);
        int z = WorldEngine.getZ(pos);
        return (x&1)|((y&1)<<2)|((z&1)<<1);
    }

    private static long makeChildPos(long basePos, int addin) {
        int lvl = WorldEngine.getLevel(basePos);
        if (lvl == 0) {
            throw new IllegalArgumentException("Cannot create a child lower than lod level 0");
        }
        return WorldEngine.getWorldSectionId(lvl-1,
                (WorldEngine.getX(basePos)<<1)|(addin&1),
                (WorldEngine.getY(basePos)<<1)|((addin>>2)&1),
                (WorldEngine.getZ(basePos)<<1)|((addin>>1)&1));
    }
}
