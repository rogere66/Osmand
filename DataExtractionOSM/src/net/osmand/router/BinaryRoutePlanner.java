package net.osmand.router;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TLongObjectHashMap;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import net.osmand.LogUtil;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapIndexReader.MapIndex;
import net.osmand.binary.BinaryMapIndexReader.SearchRequest;
import net.osmand.binary.BinaryMapIndexReader.TagValuePair;
import net.osmand.osm.LatLon;
import net.osmand.osm.MapRenderingTypes;
import net.osmand.osm.MapUtils;

import org.apache.commons.logging.Log;

public class BinaryRoutePlanner {
	
	private final static boolean PRINT_TO_CONSOLE_ROUTE_INFORMATION_TO_TEST = true;
	private final BinaryMapIndexReader[] map;
	
	
	private static final Log log = LogUtil.getLog(BinaryRoutePlanner.class);
	
	public BinaryRoutePlanner(BinaryMapIndexReader... map){
		this.map = map;
	}
	
	
	private static double squareRootDist(int x1, int y1, int x2, int y2) {
		// translate into meters 
		double dy = (y1 - y2) * 0.01863d;
		double dx = (x1 - x2) * 0.011d;
		return Math.sqrt(dx * dx + dy * dy);
	}
	

   
	public void loadRoutes(final RoutingContext ctx, int tileX, int tileY) throws IOException {
		int tileC = (tileX << ctx.getZoomToLoadTileWithRoads()) + tileY;
		if(ctx.loadedTiles.contains(tileC)){
			return;
		}
		long now = System.nanoTime();
		
		int zoomToLoad = 31 - ctx.getZoomToLoadTileWithRoads();
		SearchRequest<BinaryMapDataObject> request = BinaryMapIndexReader.buildSearchRequest(tileX << zoomToLoad,
				(tileX + 1) << zoomToLoad, tileY << zoomToLoad, 
				(tileY + 1) << zoomToLoad, 15);
		request.setSearchFilter(new BinaryMapIndexReader.SearchFilter(){
			@Override
			public boolean accept(TIntArrayList types, MapIndex index) {
				for (int j = 0; j < types.size(); j++) {
					int wholeType = types.get(j);
					TagValuePair pair = index.decodeType(wholeType);
					if (pair != null) {
						int t = wholeType & 3;
						if(t == MapRenderingTypes.POINT_TYPE){
							if(ctx.getRouter().acceptPoint(pair)){
								return true;
							}
						} else if(t == MapRenderingTypes.POLYLINE_TYPE){
							if(ctx.getRouter().acceptLine(pair)){
								return true;
							}
						}
					}
				}
				return false;
			}
		});
		for (BinaryMapIndexReader r : map) {
			r.searchMapIndex(request);
			for (BinaryMapDataObject o : request.getSearchResults()) {
				BinaryMapDataObject old = ctx.idObjects.get(o.getId());
				// sometimes way are presented only partially in one index
				if (old != null && old.getPointsLength() >= o.getPointsLength()) {
					continue;
				}
				ctx.idObjects.put(o.getId(), o);
				for (int j = 0; j < o.getPointsLength(); j++) {
					long l = (((long) o.getPoint31XTile(j)) << 31) + (long) o.getPoint31YTile(j);
					RouteSegment segment = new RouteSegment();
					segment.road = o;
					segment.segmentEnd = segment.segmentStart = j;
					if (ctx.routes.get(l) != null) {
						segment.next = ctx.routes.get(l);
					}
					ctx.routes.put(l, segment);
				}
			}
			ctx.loadedTiles.add(tileC);
			ctx.timeToLoad += (System.nanoTime() - now);
		}
	}
	

	
	public RouteSegment findRouteSegment(double lat, double lon, RoutingContext ctx) throws IOException {
		double tileX = MapUtils.getTileNumberX(ctx.getZoomToLoadTileWithRoads(), lon);
		double tileY = MapUtils.getTileNumberY(ctx.getZoomToLoadTileWithRoads(), lat);
		loadRoutes(ctx, (int) tileX , (int) tileY);
		
		RouteSegment road = null;
		double dist = 0; 
		int px = MapUtils.get31TileNumberX(lon);
		int py = MapUtils.get31TileNumberY(lat);
		for(BinaryMapDataObject r : ctx.values()){
			if(r.getPointsLength() > 1){
				double prevDist = squareRootDist(r.getPoint31XTile(0), r.getPoint31YTile(0), px, py);
				for (int j = 1; j < r.getPointsLength(); j++) {
					double cDist = squareRootDist(r.getPoint31XTile(j), r.getPoint31YTile(j), px, py);
					double mDist = squareRootDist(r.getPoint31XTile(j), r.getPoint31YTile(j), r.getPoint31XTile(j - 1), r.getPoint31YTile(j - 1));
					if (road == null || prevDist + cDist - mDist < dist) {
						road = new RouteSegment();
						road.road = r;
						road.segmentStart = j - 1;
						road.segmentEnd = j;
						dist = prevDist + cDist - mDist;
					}
					prevDist = cDist;
				}
			}
		}
		return road;
	}
	
	
	
	// TODO write unit tests
	// TODO add information about turns (?) - probably calculate additional information to show on map
	// TODO think about u-turn
	// TODO fix roundabout (?) - probably calculate additional information to show on map
	// TODO access
	// TODO fastest/shortest way
	/**
	 * Calculate route between start.segmentEnd and end.segmentStart (using A* algorithm)
	 * return list of segments
	 */
	public List<RouteSegmentResult> searchRoute(final RoutingContext ctx, RouteSegment start, RouteSegment end) throws IOException {
		
		// measure time
		ctx.timeToLoad = 0;
		ctx.visitedSegments = 0;
		long startNanoTime = System.nanoTime();

		// Initializing priority queue to visit way segments 
		Comparator<RouteSegment> segmentsComparator = new Comparator<RouteSegment>(){
			@Override
			public int compare(RouteSegment o1, RouteSegment o2) {
				return ctx.roadPriorityComparator(o1.distanceFromStart, o1.distanceToEnd, o2.distanceFromStart, o2.distanceToEnd);
			}
		};
		
		Comparator<RouteSegment> nonHeuristicSegmentsComparator = new Comparator<RouteSegment>(){
			@Override
			public int compare(RouteSegment o1, RouteSegment o2) {
				return roadPriorityComparator(o1.distanceFromStart, o1.distanceToEnd, o2.distanceFromStart, o2.distanceToEnd, 1);
			}
		};
		
		PriorityQueue<RouteSegment> graphDirectSegments = new PriorityQueue<RouteSegment>(50, segmentsComparator);
		PriorityQueue<RouteSegment> graphReverseSegments = new PriorityQueue<RouteSegment>(50, segmentsComparator);
		
		// Set to not visit one segment twice (stores road.id << X + segmentStart)
		TLongObjectHashMap<RouteSegment> visitedDirectSegments = new TLongObjectHashMap<RouteSegment>();
		TLongObjectHashMap<RouteSegment> visitedOppositeSegments = new TLongObjectHashMap<RouteSegment>();
		
		int targetEndX = end.road.getPoint31XTile(end.segmentStart);
		int targetEndY = end.road.getPoint31YTile(end.segmentStart);
		int startX = start.road.getPoint31XTile(start.segmentStart);
		int startY = start.road.getPoint31YTile(start.segmentStart);
		// for start : f(start) = g(start) + h(start) = 0 + h(start) = h(start)
		start.distanceToEnd = squareRootDist(startX, startY, targetEndX, targetEndY) / ctx.getRouter().getMaxDefaultSpeed();
		end.distanceToEnd = start.distanceToEnd;
		
		// because first point of the start is not visited do the same as in cycle but only for one point
		// it matters when start point is intersection of different roads
		// add start segment to priority queue
		visitAllStartSegments(ctx, start, graphDirectSegments, visitedDirectSegments, startX, startY);
		visitAllStartSegments(ctx, end, graphReverseSegments, visitedOppositeSegments, targetEndX, targetEndY);
		
		// final segment before end
		RouteSegment finalDirectRoute = null;
		RouteSegment finalReverseRoute = null;
		
		// Extract & analyze segment with min(f(x)) from queue while final segment is not found
		boolean inverse = false;
		
		PriorityQueue<RouteSegment>  graphSegments = inverse ? graphReverseSegments : graphDirectSegments;
		while(!graphSegments.isEmpty()){
			RouteSegment segment = graphSegments.poll();
			
			ctx.visitedSegments ++;
			// for debug purposes
			if (ctx.visitor != null) {
				ctx.visitor.visitSegment(segment);
			}
			if (!inverse) {
				RoutePair pair = processRouteSegment(ctx, end, false, graphDirectSegments, visitedDirectSegments, targetEndX,
						targetEndY, segment, visitedOppositeSegments);
				if (pair != null) {
					finalDirectRoute = pair.a;
					finalReverseRoute = pair.b;
					break;
				}
			} else {
				RoutePair pair = processRouteSegment(ctx, start, true, graphReverseSegments, visitedOppositeSegments, startX,
						startY, segment, visitedDirectSegments);
				if (pair != null) {
					finalReverseRoute = pair.a;
					finalDirectRoute = pair.b;
					break;
				}
			}
			if(graphReverseSegments.isEmpty() || graphDirectSegments.isEmpty()){
				break;
			}
			if(ctx.planRouteIn2Directions()){
//				inverse = nonHeuristicSegmentsComparator.compare(graphDirectSegments.peek(), graphReverseSegments.peek()) > 0;
				// make it more simmetrical with dynamic prioritizing it makes big sense
				inverse = !inverse;
			} else {
				// different strategy : use onedirectional graph
				inverse = !ctx.getPlanRoadDirection().booleanValue();
			}
			graphSegments = inverse ? graphReverseSegments : graphDirectSegments;
		}
		
		
		// 4. Route is found : collect all segments and prepare result
		return prepareResult(ctx, start, end, startNanoTime, finalDirectRoute, finalReverseRoute);
		
	}
	

	private void visitAllStartSegments(final RoutingContext ctx, RouteSegment start, PriorityQueue<RouteSegment> graphDirectSegments,
			TLongObjectHashMap<RouteSegment> visitedSegments, int startX, int startY) throws IOException {
		// mark as visited code seems to be duplicated
		long nt = (start.road.getId() << 8l) + start.segmentStart;
		visitedSegments.put(nt, start);
		graphDirectSegments.add(start);
		
		loadRoutes(ctx, (startX >> (31 - ctx.getZoomToLoadTileWithRoads())), (startY >> (31 - ctx.getZoomToLoadTileWithRoads())));
		long ls = (((long) startX) << 31) + (long) startY;
		RouteSegment startNbs = ctx.routes.get(ls);
		while(startNbs != null) { // startNbs.road.id >> 1, start.road.id >> 1
			if(startNbs.road.getId() != start.road.getId()){
				startNbs.parentRoute = start;
				startNbs.parentSegmentEnd = start.segmentStart;
				startNbs.distanceToEnd = start.distanceToEnd;

				// duplicated to be sure start is added
				nt = (startNbs.road.getId() << 8l) + startNbs.segmentStart;
				visitedSegments.put(nt, startNbs);
				graphDirectSegments.add(startNbs);
			}
			startNbs = startNbs.next;
		}
	}

	private RoutePair processRouteSegment(final RoutingContext ctx, RouteSegment end, boolean reverseWaySearch,
			PriorityQueue<RouteSegment> graphSegments, TLongObjectHashMap<RouteSegment> visitedSegments, int targetEndX, int targetEndY,
            RouteSegment segment, TLongObjectHashMap<RouteSegment> oppositeSegments) throws IOException {
		// Always start from segmentStart (!), not from segmentEnd
		// It makes difference only for the first start segment
		// Middle point will always be skipped from observation considering already visited
		final BinaryMapDataObject road = segment.road;
		final int middle = segment.segmentStart;
		int middlex = road.getPoint31XTile(middle);
		int middley = road.getPoint31YTile(middle);

		// 0. mark route segment as visited
		long nt = (road.getId() << 8l) + middle;
		visitedSegments.put(nt, segment);
		if (oppositeSegments.contains(nt)) {
			segment.segmentEnd = middle;
			RouteSegment opposite = oppositeSegments.get(nt);
			opposite.segmentEnd = middle;
			return new RoutePair(segment, opposite);
		}

		boolean oneway = ctx.getRouter().isOneWay(road);
		boolean minusAllowed = !oneway || reverseWaySearch;
		boolean plusAllowed = !oneway || !reverseWaySearch;

		// +/- diff from middle point
		int d = plusAllowed ? 1 : -1;
		// Go through all point of the way and find ways to continue
		// ! Actually there is small bug when there is restriction to move forward on way (it doesn't take into account)
		while (minusAllowed || plusAllowed) {
			// 1. calculate point not equal to middle
			// (algorithm should visit all point on way if it is not oneway)
			int segmentEnd = middle + d;
			if (!minusAllowed && d > 0) {
				d++;
			} else if (!plusAllowed && d < 0) {
				d--;
			} else {
				if (d <= 0) {
					d = -d + 1;
				} else {
					d = -d;
				}
			}
			if (segmentEnd < 0) {
				minusAllowed = false;
				continue;
			}
			if (segmentEnd >= road.getPointsLength()) {
				plusAllowed = false;
				continue;
			}

			// if we found end point break cycle
			long nts = (road.getId() << 8l) + segmentEnd;
			if (oppositeSegments.contains(nts)) {
				segment.segmentEnd = segmentEnd;
				RouteSegment opposite = oppositeSegments.get(nts);
				opposite.segmentEnd = segmentEnd;
				return new RoutePair(segment, opposite);
			}
			visitedSegments.put(nts, segment);

			// 2. calculate point and try to load neighbor ways if they are not loaded
			int x = road.getPoint31XTile(segmentEnd);
			int y = road.getPoint31YTile(segmentEnd);
			loadRoutes(ctx, (x >> (31 - ctx.getZoomToLoadTileWithRoads())), (y >> (31 - ctx.getZoomToLoadTileWithRoads())));
			long l = (((long) x) << 31) + (long) y;
			RouteSegment next = ctx.routes.get(l);

			// 3. get intersected ways
			if (next != null) {
				double distOnRoadToPass = squareRootDist(x, y, middlex, middley);
				double distToFinalPoint = squareRootDist(x, y, targetEndX, targetEndY);
				RouteSegment foundIntersection = processIntersectionsWithWays(ctx, graphSegments, visitedSegments, oppositeSegments,
						distOnRoadToPass, distToFinalPoint, segment, road,
						d == 0, segmentEnd, next);
				if(foundIntersection != null){
					segment.segmentEnd = segmentEnd;
					return new RoutePair(segment, foundIntersection);
				}
			}
		}
		return null;
	}
	


	private RouteSegment processIntersectionsWithWays(RoutingContext ctx, PriorityQueue<RouteSegment> graphSegments,
			TLongObjectHashMap<RouteSegment> visitedSegments, TLongObjectHashMap<RouteSegment> oppositeSegments,  
			double distOnRoadToPass, double distToFinalPoint, 
			RouteSegment segment, BinaryMapDataObject road, boolean firstOfSegment, int segmentEnd, RouteSegment next) {

		// This variables can be in routing context
		// initialize temporary lists to calculate not forbidden ways at way intersections
		ArrayList<RouteSegment> segmentsToVisitPrescripted = new ArrayList<RouteSegment>(5);
		ArrayList<RouteSegment> segmentsToVisitNotForbidden = new ArrayList<RouteSegment>(5);
		// collect time for obstacles
		double obstaclesTime = 0;
		boolean exclusiveRestriction = false;

		// 3.1 calculate time for obstacles (bumps, traffic_signals, level_crossing)
		if (firstOfSegment) {
			RouteSegment possibleObstacle = next;
			while (possibleObstacle != null) {
				obstaclesTime += ctx.getRouter().defineObstacle(possibleObstacle.road, possibleObstacle.segmentStart);
				possibleObstacle = possibleObstacle.next;
			}
		}

		// 3.2 calculate possible ways to put into priority queue
		while (next != null) {
			long nts = (next.road.getId() << 8l) + next.segmentStart;
			if(oppositeSegments.containsKey(nts)){
				RouteSegment oppSegment = oppositeSegments.get(nts);
				oppSegment.segmentEnd = next.segmentStart;
				return oppSegment;
			}
			boolean processRoad = true;
			if (ctx.isUseStrategyOfIncreasingRoadPriorities()) {
				double roadPriority = ctx.getRouter().getRoadPriorityHeuristicToIncrease(segment.road);
				double nextRoadPriority = ctx.getRouter().getRoadPriorityHeuristicToIncrease(segment.road);
				if (nextRoadPriority < roadPriority) {
					processRoad = false;
				}
			} 
			
			/* next.road.getId() >> 3 (1) != road.getId() >> 3 (1) - used that line for debug with osm map */
			// road.id could be equal on roundabout, but we should accept them
			if (!visitedSegments.contains(nts) && processRoad) {
				int type = -1;
				for (int i = 0; i < road.getRestrictionCount(); i++) {
					if (road.getRestriction(i) == next.road.getId()) {
						type = road.getRestrictionType(i);
						break;
					}
				}
				if (type == -1 && exclusiveRestriction) {
					// next = next.next; continue;
				} else if (type == MapRenderingTypes.RESTRICTION_NO_LEFT_TURN || type == MapRenderingTypes.RESTRICTION_NO_RIGHT_TURN
						|| type == MapRenderingTypes.RESTRICTION_NO_STRAIGHT_ON || type == MapRenderingTypes.RESTRICTION_NO_U_TURN) {
					// next = next.next; continue;
				} else {
					double distanceToEnd = distToFinalPoint / ctx.getRouter().getMaxDefaultSpeed();
					if(ctx.isUseDynamicRoadPrioritising()){
						double priority = ctx.getRouter().getRoadPriorityToCalculateRoute(next.road);
						distanceToEnd /= priority;
					}

					// Using A* routing algorithm
					// g(x) - calculate distance to that point and calculate time
					double speed = ctx.getRouter().defineSpeed(road);
					if (speed == 0) {
						speed = ctx.getRouter().getMinDefaultSpeed();
					}

					double distanceFromStart = segment.distanceFromStart + distOnRoadToPass / speed;
					// calculate turn time
					distanceFromStart += ctx.getRouter().calculateTurnTime(segment, next, segmentEnd);
					// add obstacles time
					distanceFromStart += obstaclesTime;

					if (next.parentRoute == null
							|| ctx.roadPriorityComparator(next.distanceFromStart, next.distanceToEnd, distanceFromStart, distanceToEnd) > 0) {
						next.distanceFromStart = distanceFromStart;
						next.distanceToEnd = distanceToEnd;
						if (next.parentRoute != null) {
							// already in queue remove it
							graphSegments.remove(next);
						}
						// put additional information to recover whole route after
						next.parentRoute = segment;
						next.parentSegmentEnd = segmentEnd;
						if (type == -1) {
							// case no restriction
							segmentsToVisitNotForbidden.add(next);
						} else {
							// case exclusive restriction (only_right, only_straight, ...)
							exclusiveRestriction = true;
							segmentsToVisitNotForbidden.clear();
							segmentsToVisitPrescripted.add(next);
						}
					}

				}
			}
			next = next.next;
		}

		// add all allowed route segments to priority queue
		for (RouteSegment s : segmentsToVisitNotForbidden) {
			graphSegments.add(s);
		}
		for (RouteSegment s : segmentsToVisitPrescripted) {
			graphSegments.add(s);
		}
		return null;
	}

	
	
	private List<RouteSegmentResult> prepareResult(RoutingContext ctx, RouteSegment start, RouteSegment end, long startNanoTime,
			RouteSegment finalDirectRoute, RouteSegment finalReverseRoute) {
		List<RouteSegmentResult> result = new ArrayList<RouteSegmentResult>();
				
		
		if (PRINT_TO_CONSOLE_ROUTE_INFORMATION_TO_TEST) {
			System.out.println("ROUTE : ");
			System.out.println("Start lat=" + MapUtils.get31LatitudeY(start.road.getPoint31YTile(start.segmentEnd)) + " lon="
					+ MapUtils.get31LongitudeX(start.road.getPoint31XTile(start.segmentEnd)));
			System.out.println("END lat=" + MapUtils.get31LatitudeY(end.road.getPoint31YTile(end.segmentStart)) + " lon="
					+ MapUtils.get31LongitudeX(end.road.getPoint31XTile(end.segmentStart)));
		}
		
		
		RouteSegment segment = finalReverseRoute;
		int parentSegmentStart = segment == null ? 0 : segment.segmentEnd; 
		while(segment != null){
			RouteSegmentResult res = new RouteSegmentResult();
			res.object = segment.road;
			res.endPointIndex = segment.segmentStart;
			res.startPointIndex = parentSegmentStart;
			parentSegmentStart = segment.parentSegmentEnd;
			segment = segment.parentRoute;
			// reverse start and end point for start if needed
			// rely that point.segmentStart <= point.segmentEnd for end, start
			if(segment == null && res.startPointIndex >= res.endPointIndex && 
					res.endPointIndex < res.object.getPointsLength() - 1){
				res.endPointIndex ++;
			}
			// do not add segments consists from 1 point
			if(res.startPointIndex != res.endPointIndex) {
				result.add(res);
			}
			res.startPoint = convertPoint(res.object, res.startPointIndex);
			res.endPoint = convertPoint(res.object, res.endPointIndex);
		}
		Collections.reverse(result);
		
		segment = finalDirectRoute;
		int parentSegmentEnd = segment == null ? 0 : segment.segmentEnd;
		while(segment != null){
			RouteSegmentResult res = new RouteSegmentResult();
			res.object = segment.road;
			res.endPointIndex = parentSegmentEnd;
			res.startPointIndex = segment.segmentStart;
			parentSegmentEnd = segment.parentSegmentEnd;
			
			segment = segment.parentRoute;
			// reverse start and end point for start if needed
			// rely that point.segmentStart <= point.segmentEnd for end, start
			if(segment == null && res.startPointIndex < res.endPointIndex){
				res.startPointIndex ++;
			}
			// do not add segments consists from 1 point
			if(res.startPointIndex != res.endPointIndex) {
				result.add(res);
			}
			res.startPoint = convertPoint(res.object, res.startPointIndex);
			res.endPoint = convertPoint(res.object, res.endPointIndex);
		}
		Collections.reverse(result);
		
		
		if (PRINT_TO_CONSOLE_ROUTE_INFORMATION_TO_TEST) {
			for (RouteSegmentResult res : result) {
				// (res.object.getId() >> 1)
				System.out.println("id=" + (res.object.getId() >> 3) + " start=" + res.startPointIndex + " end=" + res.endPointIndex);
				System.out.println("Name :" + res.object.getName());
			}
		}
		
		ctx.timeToCalculate = (System.nanoTime() - startNanoTime);
		log.info("Time to calculate : " + ctx.timeToCalculate / 1e6 +", time to load : " + ctx.timeToLoad / 1e6	 + ", loaded tiles : " + ctx.loadedTiles.size() + 
				", visited segments " + ctx.visitedSegments );
		return result;
	}
	
	private LatLon convertPoint(BinaryMapDataObject o, int ind){
		return new LatLon(MapUtils.get31LatitudeY(o.getPoint31YTile(ind)), MapUtils.get31LongitudeX(o.getPoint31XTile(ind)));
	}
	
	
	
	public static void main(String[] args) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(new File("d:\\android\\data\\Belarus.obf"), "r"); //$NON-NLS-1$ //$NON-NLS-2$
		BinaryMapIndexReader reader = new BinaryMapIndexReader(raf);
		BinaryRoutePlanner router = new BinaryRoutePlanner(reader);
		
		//double lon = 27.5967;
		//double lat = 53.9204;
		// akad
//		double lon = 27.5993;
//		double lat = 53.9186;
		double lon = 27.6024;
		double lat = 53.9141;
		
		double elon = 27.6018;
		double elat = 53.9223;
		
		RoutingContext ctx = new RoutingContext();
		
		long ms = System.currentTimeMillis();
		// find closest way
		RouteSegment start = router.findRouteSegment(lat, lon, ctx);
		if (start != null) {
			BinaryMapDataObject road = start.road;
			TagValuePair pair = road.getMapIndex().decodeType(road.getTypes()[0]);
			System.out.println("ROAD TO START " + pair.tag + " " + pair.value + " " + road.getName() + " " + start.segmentStart + " "
					+ (road.getId() >> 1));
		}
		
		RouteSegment end = router.findRouteSegment(elat, elon, ctx);
		if (end != null) {
			BinaryMapDataObject road = end.road;
			TagValuePair pair = road.getMapIndex().decodeType(road.getTypes()[0]);
			System.out.println("ROAD TO END " + pair.tag + " " + pair.value + " " + road.getName() + " " + end.segmentStart + " "
					+ (road.getId() >> 1));
		}
		
		

//		double tileX = Math.round(MapUtils.getTileNumberX(ZOOM_LOAD_TILES, lon));
//		double tileY = Math.round(MapUtils.getTileNumberY(ZOOM_LOAD_TILES, lat));
		// preload neighboors
//		router.loadRoutes(ctx, (int) tileX, (int) tileY);
//		router.loadRoutes(ctx, (int) tileX - 1, (int) tileY);
//		router.loadRoutes(ctx, (int) tileX, (int) tileY - 1);
//		router.loadRoutes(ctx, (int) tileX - 1, (int) tileY - 1);
		  
		
		
		for(RouteSegmentResult s : router.searchRoute(ctx, start, end)){
			double dist = MapUtils.getDistance(s.startPoint, s.endPoint);
			System.out.println("Street " + s.object.getName() + " distance " + dist);
		}
		
		Collection<BinaryMapDataObject> res = ctx.values();
		System.out.println(res.size() + " objects for " + (System.currentTimeMillis() - ms) + " ms");
		
		LatLon ls = new LatLon(0, 5);
		LatLon le = new LatLon(1, 5);
		System.out.println("X equator " + MapUtils.getDistance(ls, le) / (MapUtils.get31TileNumberX(ls.getLongitude()) - MapUtils.get31TileNumberX(le.getLongitude())));
		System.out.println("Y equator " + MapUtils.getDistance(ls, le) / (MapUtils.get31TileNumberY(ls.getLatitude()) - MapUtils.get31TileNumberY(le.getLatitude())));
		
//		for(BinaryMapDataObject r : res){
//			TagValuePair pair = r.mapIndex.decodeType(r.getTypes()[0]);
//			if(r.name != null){
//				System.out.println(pair.tag + " " + pair.value + " " + r.name );
//			} else {
//				System.out.println(pair.tag + " " + pair.value + " " + (r.id >>13));
//			}
//		}
	}

	public interface RouteSegmentVisitor {
		
		public void visitSegment(RouteSegment segment);
	}
	
	
	
	
	/*public */static int roadPriorityComparator(double o1DistanceFromStart, double o1DistanceToEnd, 
			double o2DistanceFromStart, double o2DistanceToEnd, double heuristicCoefficient ) {
		// f(x) = g(x) + h(x)  --- g(x) - distanceFromStart, h(x) - distanceToEnd (not exact)
		return Double.compare(o1DistanceFromStart + heuristicCoefficient * o1DistanceToEnd, 
				o2DistanceFromStart + heuristicCoefficient *  o2DistanceToEnd);
	}

	public static class RouteSegment {
		int segmentStart = 0;
		int segmentEnd = 0;
		BinaryMapDataObject road;
		// needed to store intersection of routes
		RouteSegment next = null;
		
		// search context (needed for searching route)
		// Initially it should be null (!) because it checks was it segment visited before
		RouteSegment parentRoute = null;
		int parentSegmentEnd = 0;
		
		// distance measured in time (seconds)
		double distanceFromStart = 0;
		double distanceToEnd = 0;
		
		public BinaryMapDataObject getRoad() {
			return road;
		}
	}

	private static class RoutePair {
		RouteSegment a;
		RouteSegment b;
		public RoutePair(RouteSegment a, RouteSegment b) {
			super();
			this.a = a;
			this.b = b;
		}
		
		
	}

	
}
