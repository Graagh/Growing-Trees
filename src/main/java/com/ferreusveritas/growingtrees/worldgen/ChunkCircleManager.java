package com.ferreusveritas.growingtrees.worldgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.TreeMap;
import java.util.Map.Entry;

import com.ferreusveritas.growingtrees.util.Circle;
import com.ferreusveritas.growingtrees.util.Dir;
import com.ferreusveritas.growingtrees.util.Vec2d;

import net.minecraft.world.gen.NoiseGeneratorPerlin;

public class ChunkCircleManager {

	static private ChunkCircleManager instance = new ChunkCircleManager();	
	NoiseGeneratorPerlin noiseGenerator;
	
	HashMap<Vec2d, ChunkCircleSet> chunkCircles;
	
	public static ChunkCircleManager getInstance(){
		return instance;
	}
	
	public static ChunkCircleManager newInstance(){
		instance = new ChunkCircleManager();
		return instance;
	}
	
	public ChunkCircleManager() {
		chunkCircles = new HashMap<Vec2d, ChunkCircleSet>();
		noiseGenerator = new NoiseGeneratorPerlin(new Random(96), 1);
	}
	
	public ArrayList<Circle> getCircles(Random random, int chunkX, int chunkZ){	
		ChunkCircleSet cSet = getChunkCircleSet(chunkX, chunkZ);
		if(cSet.generated){
			return getChunkCircles(chunkX, chunkZ);
		} else {
			return generateCircles(random, chunkX, chunkZ);
		}
	}
	
	private ArrayList<Circle> generateCircles(Random random, int chunkX, int chunkZ){
    			
    	ArrayList<Circle> circles = new ArrayList<Circle>(64);//64 is above the typical range to expect for 9 chunks
    	ArrayList<Circle> unsolvedCircles = new ArrayList<Circle>(64);
    	
    	//Collect circles
    	for(Dir dir: Dir.SURROUND){
    		getChunkCircles(circles, chunkX + dir.xOffset, chunkZ + dir.zOffset);
    	}
    	
    	int chunkXStart = chunkX << 4;
    	int chunkZStart = chunkZ << 4;
    	
    	for(Circle c: circles){
    		c.edgeMask(chunkXStart, chunkZStart);//Do edge masking
    	}

    	//Mask out circles against one another
    	for(int i = 0; i < circles.size() - 1; i++){
    		for(int j = i + 1; j < circles.size(); j++){
    			CircleHelper.maskCircles(circles.get(i), circles.get(j));
    		}
    	}

    	//Handle no existing circles by creating a single circle to build off of
    	if(circles.size() == 0){
    		int x = chunkXStart + random.nextInt(16);
    		int z = chunkZStart + random.nextInt(16);
    		int radius = CircleHelper.getRadiusAtCoords(x, z, noiseGenerator);
    		//int radius = 5;
    		Circle rootCircle = new Circle(x, z, radius);
    		rootCircle.real = true;
    		circles.add(rootCircle);
    	}
    	
    	//Gather the unsolved circles into a list
    	CircleHelper.gatherUnsolved(unsolvedCircles, circles);
    	
    	int count = 0;
    	
    	//Keep solving all unsolved circles until there aren't any more to solve.
    	while(!unsolvedCircles.isEmpty()){
    		Circle master = unsolvedCircles.get(0);//Any circle will do.  May as well be the first.
    					
    		//int radius = random.nextInt(8) == 0 ? 3 : 2;
    		int radius = CircleHelper.getRadiusAtCoords(master, noiseGenerator);
    		
    		Circle slave = CircleHelper.findSecondCircle(master, radius);//Create a second circle tangential to the master circle.
    		Vec2d slavePos = new Vec2d(slave);//Cache slave position

    		//Mask off the master so it won't happen again.
    		master.arc |= 1 << master.getFreeBit();//Clear specific arc bit for good measure
    		CircleHelper.maskCircles(master, slave, true);
			
			//Create a list of existing circles that are intersecting with this circle.  List is ordered by penetration depth.
    		int i = 0;
    		TreeMap<Integer, Circle> intersecting = new TreeMap<Integer, Circle>();
    		for(Circle c: circles){
    			if(slave.doCirclesIntersect(c)){
    				int depth = 16 + (int)c.circlePenetration(slave);
    				intersecting.put(depth << 8 | i++, c);
    			}
    		}

    		//Run through all of the circles that were intersecting
    		for(Entry<Integer, Circle> entry: intersecting.entrySet()){
    			Circle master1 = master;//Cache master value because we do swapping later
    			Circle master2 = entry.getValue();

    			//Determine handedness of 3rd circle interaction
    			int cross = Vec2d.crossProduct(new Vec2d(slavePos).sub(master1),new Vec2d(master2).sub(master1));
    			if(cross < 0){//Swap circles if the cross product is negative
    				Circle temp = master2;
    				master2 = master1;
    				master1 = temp;
    			}

    			slave = CircleHelper.findThirdCircle(master1, master2, radius);//Attempt to triangulate a circle position that is touching tangentially to both master circles
    			if(slave != null){//Found a 3rd circle candidate
    				for(int ci = 0; ci < circles.size(); ci++){
    					Circle c = circles.get(ci);
    					if(slave.doCirclesIntersect(c)){//See if this new circle intersects with any of the existing circles. If it does then..
    						if(c.real || (!c.real && !slave.isInCenterChunk(chunkXStart, chunkZStart)) ){
    							slave = null;//Discard the circle because it's intersecting with an existing real circle
    							break;//We needn't continue since we've proven that the circle intersects with any circle
    						} else {//The overlapping circle is not real.. but the slave circle is.
    							CircleHelper.fastRemove(circles, ci--);//Delete the offending non-real circle. The order of the circles is unimportant
    						} 
    					}
    				}
    			}

    			if(slave != null){
    				break;//We found a viable circle.. time to move on
    			}
    		}

    		if(slave != null){//The circle has passed all of the non-intersection tests.  Let's add it to the list of circles
    			slave.edgeMask(chunkXStart, chunkZStart);//Set the proper mask for whatever chunk this circle resides.
    			slave.real = slave.isInCenterChunk(chunkXStart, chunkZStart);//Only circles created in the center chunk are real
    			unsolvedCircles.add(slave);//The new circle is necessarily unsolved and we need it in this list for the next step.
    			CircleHelper.solveCircles(unsolvedCircles, circles);//run all of the unsolved circles again
    			circles.add(slave);//add the new circle to the full list
    		}
    		
    		CircleHelper.gatherUnsolved(unsolvedCircles, circles);//List up the remaining unsolved circles and try again

    		//For debug purposes
    		if(++count > 64 && !unsolvedCircles.isEmpty()){//It shouldn't over take 64 iterations to solve all of the circles
    			System.err.println("-----" + unsolvedCircles.size() + " unsolved circles-----");
    			System.err.println("@ chunk x:" + chunkX + ", z:" + chunkZ);
    			System.err.println("after " + count + " iterations" );
    			for(Circle c: circles){
    				System.err.println((c.hasFreeAngles() ? "->" : "  ") +  c);
    			}
    			CircleDebug.outputCirclesToPng(circles, chunkX, chunkZ, "");
    			break;//Something went terribly wrong and we shouldn't hang the system for it.
    		}
    	}
    	
    	//Add circles to circle set
    	ChunkCircleSet cSet = getChunkCircleSet(chunkX, chunkZ);
    	cSet.generated = true;
    	
    	for(Circle c: circles){
    		if(c.isInCenterChunk(chunkXStart, chunkZStart)){
    			cSet.addCircle(c);
    		}
    	}
    	circles.clear();

    	return cSet.getCircles(circles, chunkX, chunkZ);
	}

	private ChunkCircleSet getChunkCircleSet(int chunkX, int chunkZ){
		Vec2d key = new Vec2d(chunkX, chunkZ);
		ChunkCircleSet cSet;
		
		if(chunkCircles.containsKey(key)){
			cSet = chunkCircles.get(key);
		} else {
			cSet = new ChunkCircleSet();
			chunkCircles.put(key, cSet);
		}		
		
		return cSet;
	}
	
	public byte[] getChunkCircleData(int chunkX, int chunkZ) {
		return getChunkCircleSet(chunkX, chunkZ).getCircleData();
	}
	
	public void setChunkCircleData(int chunkX, int chunkZ, byte[] circleData) {
		getChunkCircleSet(chunkX, chunkZ).setCircleData(circleData);
	}
	
	public void unloadChunkCircleData(int chunkX, int chunkZ){
		chunkCircles.remove(new Vec2d(chunkX, chunkZ));
	}
	
	private ArrayList<Circle> getChunkCircles(int chunkX, int chunkZ){
		return getChunkCircles(new ArrayList<Circle>(), chunkX, chunkZ);
	}
	
	private ArrayList<Circle> getChunkCircles(ArrayList<Circle> circles, int chunkX, int chunkZ){
		ChunkCircleSet cSet = getChunkCircleSet(chunkX, chunkZ);
		cSet.getCircles(circles, chunkX, chunkZ);
		return circles;
	}
	
}
