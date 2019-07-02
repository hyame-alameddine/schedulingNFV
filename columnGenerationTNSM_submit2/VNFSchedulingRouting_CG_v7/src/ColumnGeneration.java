import ilog.concert.IloException;
import ilog.cplex.IloCplex;
import ilog.cplex.IloCplex.IncumbentCallback;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import CallBacks.MyIncumbentCallBack;
import CallBacks.MyMIPCallBack;
import CallBacks.PricingIncumbentCallBack;
import CallBacks.PricingIncumbentCallBackForThread;
import HelperClasses.Output;
import HelperClasses.ServicesDriver;
import Model.CGMasterModel;
import Model.CGPricingModel;
import Model.CGPricingModelThread;
import Model.Configuration;
import Model.ILPModelModified;
import NFV.Service;
import Network.Network;
import Test.PricingTest;


public class ColumnGeneration {

	private Network network;
	private ArrayList<Service> services;
	private int DELTA;
	public ColumnGeneration (Network network, ArrayList<Service> services, int DELTA)
	{
		this.network = network;
		this.services = services;
		this.DELTA = DELTA;
	}
	
	
	/**
	 * This function executes the column generation by mapping and scheduling the services
	 * Getting an upper bound on DELTA based on the completion time of the last service
	 * Executing column generation and reporting the results
	 * 
	 * @param useCallback set to true if we want to set the pricing to stop at a feasible solution
	 * 
	 * @return Array<int []> int [0]=execution time, int[1] objective value, first array for LP and the second for ILP
	 * it will return null if column generation was not executed due to having a service which caused a mapping exception
	 * @param pricingFilePath = "pricingResults/pRes_";//"pricingResults/pRes_"+this.service.getId()+"_"+iterations+".txt";
	 * @param masterFilePath = "masterResults/mRes_";// "masterResults/mRes_"+iterations+".txt";
	 * @param ilpFilePath ="masterResults/ILP.txt";
	 * 
	 * @param useILPSolution provide the ILP solution as initial input for the master
	 * @throws IloException
	 * @throws IOException
	 */
	public ArrayList<double[]> executeColumnGeneration( boolean useIncumbentCallback,  String masterFilePath,String ilpFilePath, String PricingFilePath, boolean useILPSolution) throws IloException, IOException
	{
		
		Configuration c;
		double reducedCost = 0;
		int pricingIterations=1;//start from iteration 1 to be able to track lambda on the name of the pricing file for the selected lambdas, and start it from 0 for the master
		int masterIterations=0;//start it from 0 for the master
		int initialColumnsSet = 1; //number of columns per service generated by the heuristic as basic solution for the master
		int pricingwithNegativeRC =0;
		
		//runtime variables
		double startlp =0;
		double endlp =0;
		double startIlp =0;
		double endIlp =0;
		
		//report test results
		ArrayList<double[]> testResults = new ArrayList<double[]>();
		double [] results = new double[2];
		
		int[][]v ;
		int[][][][] o;
		int[][][][] r;
		ArrayList <Configuration> configurations = new ArrayList<Configuration>();	
		ArrayList <int[][][][]> modelInput ;
		SchedulingHeuristic sh = new SchedulingHeuristic(this.network,"logs/ServicesHeuristic.txt");
	
		
		//map and schedule the services
		configurations = sh.mapScheduleService(this.services, this.DELTA,0);
					
		//check if any of the services was not scheduled, mapped or routed, then return null and do not execute columns generation
		if (configurations == null)
		{
			return null;
		}
		
		//update delta to its upper bound which is the completion time of the last service
		this.DELTA = this.services.get(this.services.size()-1).getCompletionTime(); 	

		//override the configurations given by the heuristic
		if (useILPSolution)
		{
			ArrayList<Configuration>ilpConfigurations;
			
			ILPModelModified ilp =  new ILPModelModified(this.network, this.services, this.DELTA);
	    	ilp.buildILPModel();
	    	ilp.runILPModel("testResults/ILP/ILPResults_1.txt", 0);
	    	ilpConfigurations = ilp.buildServiceConfigurations();
		
	    	for (int i=0; i<ilpConfigurations.size(); i++)
	    	{
	    		configurations.add(ilpConfigurations.get(i));
	    	}
			ilp.cplex.end();
		}	
		
		//prepare the initial configurations for the master
		modelInput = sh.convertConfigurations(configurations, this.DELTA,initialColumnsSet);		
		v = modelInput.get(0)[0][0];
		o = modelInput.get(1);//lsdeltac
		r = modelInput.get(2);//fsdeltac
		
		//build the master model	
		CGMasterModel masterModel = new CGMasterModel(initialColumnsSet,this.services.size(),this.DELTA, this.network.getLinkList().length,this.network.getVNFNb(), v, r, o,this.network.getLinksCapacities());		
		masterModel.buildMaster();
		
		//add configurations to the columns array in the master. The configuration need to be updated so o and r have the same timeslots as the updated delta
		//masterModel.columns.addAll(configurations);
				
		startlp = System.currentTimeMillis();
		while (true)
		{
			masterModel.runMasterModel(masterFilePath+"_"+masterIterations+".txt",masterIterations );

			for (int i = 0; i<services.size(); i++)
			{				
				CGPricingModel pricingModel = new CGPricingModel(services.get(i), this.network, masterModel, this.DELTA,0);
				pricingModel.buildPricingModel(null);
				
				//Tell cplex to stop running the pricing at a feasible solution with a certain gap to gain time
				if (useIncumbentCallback)
				{
					pricingModel.cplex.use(new MyIncumbentCallBack());//(new MyMIPCallBack(20));
				}
				
				c = pricingModel.runPricingModel(PricingFilePath+"_"+services.get(i).getId()+"_"+pricingIterations+".txt", pricingIterations);
				
				//get the reduced cost of the pricing 
				reducedCost = pricingModel.cplex.getObjValue();		
			
				if (useIncumbentCallback)
				{
					pricingModel.cplex.clearCallbacks();
				}
							
				//compare to a very small value and not 0 since the reduced cost is double and may have value 0.0000 which will not be considered <=0
				if (reducedCost <=1e-10)
				{
					//if reduced cost<=0, no need to add column for this service
					//count the number of pricing having RC<=0, pricing for all services at the same iteration <=0 stop the CG
					pricingwithNegativeRC++;
					
					//this just to mention that no column was added to the service at this iteration so we can read easily the chosen column (if lambda[0][1] = 1; then pRes_0_1 column was chosen) 
					masterModel.cut[services.get(i).getId()].add(null);
				}
				else if (!masterModel.columnExists(c))
				{
					//make sure of the validity of the configuration returned by the pricing
					/*ptest = new PricingTest(pricingModel);
					if (!ptest.verifyConfiguration())
					{
						return null;
					}*/
					masterModel.addColumnToMaster(c);
				}
				pricingModel.cplex.end();	
			}			
			
			//optimal solution is reached
			if (pricingwithNegativeRC == services.size())
			{
				break;
			}
		
			pricingwithNegativeRC=0;
			pricingIterations++;
			masterIterations++;
		}		
		
		
		endlp = System.currentTimeMillis();
		results[0] = endlp - startlp;
		results[1] = masterModel.lpObjectiveValue;
		testResults.add(results);
		
		//run ILP and add results to the array	
		startIlp = System.currentTimeMillis();
		masterModel.runILPMasterModel(ilpFilePath);
		endIlp = System.currentTimeMillis();
		
		results = new double[2];
		results[0] = endIlp - startIlp;
		results[1] = masterModel.IlpObjectiveValue;
		testResults.add(results);
		
		results = new double[2];
		results[0] = pricingIterations;
		results[1] = DELTA;
		testResults.add(results);
		
		masterModel.cplex.end();
		
		return testResults;
	}
	
	
	/**
	 * This function is used to run column generation with diversification
	 * Pricing will run to optimality. During the run, the incumbent callback is called and all the incumbent solution will be added as columns to the master
	 * 
	 * @param useIncumbentCallback if set to true the incumbent solution will used for diversification
	 * @param useHeuristicDiversification if set to true configuration generated by the heuristic will be added to the master as diversification
	 * @param startTimeslot timeslot at which the services schedule should start mainly used for the pricing and the diversification heuristic
	 * @param nbOfConfigPerService number of columns to generate for each service by the diversification heuristic
	 * 
	 * @param masterFilePath file where to report the results of the master
	 * @param ilpFilePath file where to report the results of the CG ILP
	 * @param PricingFilePath file  where to report the results of the CG pricing -> 
	 * testResults/pRes_iteration_ServiceId_ColumnId; Here the column ID is as in lambda[s][c] which is different that the Configuration.id
	 * @return array of CG LP and ILP objective value and runtime
	 * @throws IloException
	 * @throws IOException
	 */
	public ArrayList<double[]> executeColumnGenerationDiversification( boolean useIncumbentCallback, boolean useHeuristicDiversification, int startTimeslot, int nbOfConfigPerService, String masterFilePath,String ilpFilePath, String pricingFilePath) throws IloException, IOException
	{

	  	PricingIncumbentCallBack incumbentCallback ;
	  	
		Configuration c;
		double reducedCost = 0;
		int pricingIterations=1;//start from iteration 1 to be able to track lambda on the name of the pricing file for the selected lambdas, and start it from 0 for the master
		int masterIterations=0;//start it from 0 for the master
		int initialColumnsSet = 1; //number of columns per service generated by the heuristic as basic solution for the master
		int pricingwithNegativeRC =0;
		
		//runtime variables
		double startlp =0;
		double endlp =0;
		double startIlp =0;
		double endIlp =0;
		
		//report test results
		ArrayList<double[]> testResults = new ArrayList<double[]>();
		double [] results = new double[2];

		int[][]v ;
		int[][][][] o;
		int[][][][] r;
		ArrayList <Configuration> configurations = new ArrayList<Configuration>();	
		ArrayList <int[][][][]> modelInput ;
		SchedulingHeuristic sh = new SchedulingHeuristic(this.network,"logs/ServicesHeuristic.txt");
	
		
		//map and schedule the services
		configurations = sh.mapScheduleService(this.services, this.DELTA,0);
					
		//check if any of the services was not scheduled, mapped or routed, then return null and do not execute columns generation
		if (configurations == null)
		{
			return null;
		}
		
		//update delta to its upper bound which is the completion time of the last service
		this.DELTA = this.services.get(this.services.size()-1).getCompletionTime(); 	

		//prepare the initial configurations for the master
		modelInput = sh.convertConfigurations(configurations, this.DELTA,initialColumnsSet);		
		v = modelInput.get(0)[0][0];
		o = modelInput.get(1);//lsdeltac
		r = modelInput.get(2);//fsdeltac
		
		//build the master model	
		CGMasterModel masterModel = new CGMasterModel(initialColumnsSet,this.services.size(),this.DELTA, this.network.getLinkList().length,this.network.getVNFNb(), v, r, o,this.network.getLinksCapacities());		
		masterModel.buildMaster();

		//add configurations to the columns array in the master. The configuration need to be updated so o and r have the same timeslots as the updated delta
		//masterModel.columns.addAll(configurations);
		
		startlp = System.currentTimeMillis();
		while (true)
		{
			masterModel.runMasterModel(masterFilePath+"_"+masterIterations+".txt",masterIterations );

			for (int i = 0; i<services.size(); i++)
			{				
				CGPricingModel pricingModel = new CGPricingModel(services.get(i), this.network, masterModel, this.DELTA,startTimeslot);
				pricingModel.buildPricingModel(null);
								
								
				//pricingResultPath = pricingFilePath+"_"+pricingIterations+"_"+services.get(i).getId()+"_"+masterModel.cut[services.get(i).getId()].getSize()+".txt";
				if(useIncumbentCallback)
				{
					incumbentCallback = new PricingIncumbentCallBack(pricingModel, masterModel);
					pricingModel.cplex.use(incumbentCallback);
				}			  	 
			  	c = pricingModel.runPricingModel(pricingFilePath+"_"+services.get(i).getId()+"_"+pricingIterations+"_Div.txt", pricingIterations);
				
				//get the reduced cost of the pricing 
				reducedCost = pricingModel.cplex.getObjValue();		
				
				if(useIncumbentCallback)
				{
					pricingModel.cplex.clearCallbacks();			
				}
				
				//compare to a very small value and not 0 since the reduced cost is double and may have value 0.0000 which will not be considered <=0
				if (reducedCost <=1e-10)
				{
					//if reduced cost<=0, no need to add column for this service
					//count the number of pricing having RC<=0, pricing for all services at the same iteration <=0 stop the CG
					pricingwithNegativeRC++;
					
					//this just to mention that no column was added to the service at this iteration so we can read easily the chosen column (if lambda[0][1] = 1; then pRes_0_1 column was chosen) 
					masterModel.cut[services.get(i).getId()].add(null);
				}
				//make sure that the column is not already added to the master to reduce master execution time
				else if (!masterModel.columnExists(c))
	        	{
					masterModel.addColumnToMaster(c);
				}
				pricingModel.cplex.end();	
			}			
			
			//optimal solution is reached
			if (pricingwithNegativeRC == services.size())
			{
				break;
			}
		
			pricingwithNegativeRC=0;
			pricingIterations++;
			masterIterations++;
		}	
		
		
		endlp = System.currentTimeMillis();
		results[0] = endlp - startlp;
		results[1] = masterModel.lpObjectiveValue;
		testResults.add(results);
		
		//add all incumbent solutions before running ILP; this will only add configurations that do not already exist in the master
		if(useIncumbentCallback)
		{
			masterModel.addColumnsToMaster(masterModel.incumbentColumns);
		}
		
		if(useHeuristicDiversification)
		{
			ArrayList<Configuration> diversificationConfigurations = sh.generateDiversificationConfigurations(services, this.DELTA, startTimeslot,nbOfConfigPerService );
			masterModel.addColumnsToMaster(diversificationConfigurations);			
		}		

		
		//run ILP after adding incumbent columns and add results to the array	
		startIlp = System.currentTimeMillis();
		masterModel.runILPMasterModel(ilpFilePath);
		endIlp = System.currentTimeMillis();
		
		results = new double[2];
		results[0] = endIlp - startIlp;
		results[1] = masterModel.IlpObjectiveValue;
		testResults.add(results);
		
		results = new double[2];
		results[0] = pricingIterations;
		results[1] = this.DELTA;
		testResults.add(results);
		
		masterModel.cplex.end();
		
		return testResults;
	}
	
	
	/**
	 * This function executes the column generation by mapping and scheduling the services
	 * Getting an upper bound on DELTA based on the completion time of the last service
	 * Executing column generation and reporting the results
	 * 
	 * @param useCallback set to true if we want to set the pricing to stop at a feasible solution
	 * 
	 * @return Array<int []> int [0]=execution time, int[1] objective value, first array for LP and the second for ILP
	 * it will return null if column generation was not executed due to having a service which caused a mapping exception
	 * @param pricingFilePath = "pricingResults/pRes_";//"pricingResults/pRes_"+this.service.getId()+"_"+iterations+".txt";
	 * @param masterFilePath = "masterResults/mRes_";// "masterResults/mRes_"+iterations+".txt";
	 * @param ilpFilePath ="masterResults/ILP.txt";
	 * 
	 * @param useILPSolution provide the ILP solution as initial input for the master
	 * @throws IloException
	 * @throws IOException
	 */
	public ArrayList<double[]> executeColumnGenerationWithThread( boolean useIncumbentCallback,  String masterFilePath,String ilpFilePath, String PricingFilePath, boolean useILPSolution) throws IloException, IOException
	{
		
		Configuration c;
		double reducedCost = 0;
		int pricingIterations=1;//start from iteration 1 to be able to track lambda on the name of the pricing file for the selected lambdas, and start it from 0 for the master
		int masterIterations=0;//start it from 0 for the master
		int initialColumnsSet = 1; //number of columns per service generated by the heuristic as basic solution for the master
		int pricingwithNegativeRC =0;
		
		//runtime variables
		double startlp =0;
		double endlp =0;
		double startIlp =0;
		double endIlp =0;
		
		//report test results
		ArrayList<double[]> testResults = new ArrayList<double[]>();
		double [] results = new double[2];
		
		int[][]v ;
		int[][][][] o;
		int[][][][] r;
		ArrayList <Configuration> configurations = new ArrayList<Configuration>();	
		ArrayList <int[][][][]> modelInput ;
		ArrayList <CGPricingModelThread> pricingThreads = new ArrayList<CGPricingModelThread>() ;
		CGPricingModelThread pricingModelThread;
		SchedulingHeuristic sh = new SchedulingHeuristic(this.network,"logs/ServicesHeuristic.txt");
	
		
		//map and schedule the services
		configurations = sh.mapScheduleService(this.services, this.DELTA,0);
					
		//check if any of the services was not scheduled, mapped or routed, then return null and do not execute columns generation
		if (configurations == null)
		{
			return null;
		}
		
		//update delta to its upper bound which is the completion time of the last service
		this.DELTA = this.services.get(this.services.size()-1).getCompletionTime(); 	

		//override the configurations given by the heuristic
		if (useILPSolution)
		{
			ArrayList<Configuration>ilpConfigurations;
			
			ILPModelModified ilp =  new ILPModelModified(this.network, this.services, this.DELTA);
	    	ilp.buildILPModel();
	    	ilp.runILPModel("testResults/ILP/ILPResults_1.txt", 0);
	    	ilpConfigurations = ilp.buildServiceConfigurations();
		
	    	for (int i=0; i<ilpConfigurations.size(); i++)
	    	{
	    		configurations.add(ilpConfigurations.get(i));
	    	}
			ilp.cplex.end();
		}	
		
		//prepare the initial configurations for the master
		modelInput = sh.convertConfigurations(configurations, this.DELTA,initialColumnsSet);		
		v = modelInput.get(0)[0][0];
		o = modelInput.get(1);//lsdeltac
		r = modelInput.get(2);//fsdeltac
		
		//build the master model	
		CGMasterModel masterModel = new CGMasterModel(initialColumnsSet,this.services.size(),this.DELTA, this.network.getLinkList().length,this.network.getVNFNb(), v, r, o,this.network.getLinksCapacities());		
		masterModel.buildMaster();
		
		//add configurations to the columns array in the master. The configuration need to be updated so o and r have the same timeslots as the updated delta
		//masterModel.columns.addAll(configurations);
				
		startlp = System.currentTimeMillis();
		while (true)
		{
			masterModel.runMasterModel(masterFilePath+"_"+masterIterations+".txt",masterIterations );

			for (int i = 0; i<services.size(); i++)
			{				
				pricingModelThread = new CGPricingModelThread(services.get(i), this.network, masterModel, this.DELTA,0, PricingFilePath+"_"+services.get(i).getId()+"_"+pricingIterations+".txt");
				pricingModelThread.buildPricingModel(null);
				
				//Tell cplex to stop running the pricing at a feasible solution with a certain gap to gain time
				if (useIncumbentCallback)
				{
					pricingModelThread.cplex.use(new MyIncumbentCallBack());//(new MyMIPCallBack(20));
				}
				
				//start the pricing thread that will call the run method
				pricingModelThread.start();
				pricingThreads.add(pricingModelThread);
			}
			
			
			for (int i = 0; i<pricingThreads.size(); i++)
			{
				try {
					pricingModelThread = pricingThreads.get(i);
					//join all threads so we make sure that all threads are done execution before proceeding
					pricingModelThread.join();
										
					reducedCost = pricingModelThread.cplex.getObjValue();		
					
					if (useIncumbentCallback)
					{
						pricingModelThread.cplex.clearCallbacks();
					}
								
					//compare to a very small value and not 0 since the reduced cost is double and may have value 0.0000 which will not be considered <=0
					if (reducedCost <=1e-10)
					{
						//if reduced cost<=0, no need to add column for this service
						//count the number of pricing having RC<=0, pricing for all services at the same iteration <=0 stop the CG
						pricingwithNegativeRC++;
						
						//this just to mention that no column was added to the service at this iteration so we can read easily the chosen column (if lambda[0][1] = 1; then pRes_0_1 column was chosen) 
						masterModel.cut[pricingModelThread.getService().getId()].add(null);
					}
					else if (!masterModel.columnExists(pricingModelThread.conf))
					{					
						masterModel.addColumnToMaster(pricingModelThread.conf);
					}
					
					pricingModelThread.cplex.end();	
				
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
				
			//empty the threads array for the next iteration or we will end up in an infinite loop
			pricingThreads = new ArrayList<CGPricingModelThread>() ;
			
			//optimal solution is reached
			if (pricingwithNegativeRC == services.size())
			{
				break;
			}
		
			pricingwithNegativeRC=0;
			pricingIterations++;
			masterIterations++;
		}		
		
		
		endlp = System.currentTimeMillis();
		results[0] = endlp - startlp;
		results[1] = masterModel.lpObjectiveValue;
		testResults.add(results);
		
		//run ILP and add results to the array	
		startIlp = System.currentTimeMillis();
		masterModel.runILPMasterModel(ilpFilePath);
		endIlp = System.currentTimeMillis();
		
		results = new double[2];
		results[0] = endIlp - startIlp;
		results[1] = masterModel.IlpObjectiveValue;
		testResults.add(results);
		
		results = new double[2];
		results[0] = pricingIterations;
		results[1] = DELTA;
		testResults.add(results);
		
		masterModel.cplex.end();
		
		return testResults;
	}
	
	
	/**
	 * This function is used to run column generation with diversification
	 * Pricing will run to optimality. During the run, the incumbent callback is called and all the incumbent solution will be added as columns to the master
	 * 
	 * @param useIncumbentCallback if set to true the incumbent solution will used for diversification
	 * @param useHeuristicDiversification if set to true configuration generated by the heuristic will be added to the master as diversification
	 * @param startTimeslot timeslot at which the services schedule should start mainly used for the pricing and the diversification heuristic
	 * @param nbOfConfigPerService number of columns to generate for each service by the diversification heuristic
	 * 
	 * @param masterFilePath file where to report the results of the master
	 * @param ilpFilePath file where to report the results of the CG ILP
	 * @param PricingFilePath file  where to report the results of the CG pricing -> 
	 * testResults/pRes_iteration_ServiceId_ColumnId; Here the column ID is as in lambda[s][c] which is different that the Configuration.id
	 * @return array of CG LP and ILP objective value and runtime
	 * @throws IloException
	 * @throws IOException
	 */
	public ArrayList<double[]> executeColumnGenerationDiversificationWithThread( boolean useIncumbentCallback, boolean useHeuristicDiversification, int startTimeslot, int nbOfConfigPerService, String masterFilePath,String ilpFilePath, String pricingFilePath) throws IloException, IOException
	{

	  	PricingIncumbentCallBackForThread incumbentCallback ;
	  	CGPricingModelThread pricingModelThread;
	  	
		Configuration c;
		double reducedCost = 0;
		int pricingIterations=1;//start from iteration 1 to be able to track lambda on the name of the pricing file for the selected lambdas, and start it from 0 for the master
		int masterIterations=0;//start it from 0 for the master
		int initialColumnsSet = 1; //number of columns per service generated by the heuristic as basic solution for the master
		int pricingwithNegativeRC =0;
		
		//runtime variables
		double startlp =0;
		double endlp =0;
		double startIlp =0;
		double endIlp =0;
		
		//report test results
		ArrayList<double[]> testResults = new ArrayList<double[]>();
		double [] results = new double[2];

		int[][]v ;
		int[][][][] o;
		int[][][][] r;
		ArrayList <Configuration> configurations = new ArrayList<Configuration>();	
		ArrayList <int[][][][]> modelInput ;
		ArrayList <CGPricingModelThread> pricingThreads = new ArrayList<CGPricingModelThread>() ;
		SchedulingHeuristic sh = new SchedulingHeuristic(this.network,"logs/ServicesHeuristic.txt");
	
		
		//map and schedule the services
		configurations = sh.mapScheduleService(this.services, this.DELTA,0);
					
		//check if any of the services was not scheduled, mapped or routed, then return null and do not execute columns generation
		if (configurations == null)
		{
			return null;
		}
		
		//update delta to its upper bound which is the completion time of the last service
		this.DELTA = this.services.get(this.services.size()-1).getCompletionTime(); 	

		//prepare the initial configurations for the master
		modelInput = sh.convertConfigurations(configurations, this.DELTA,initialColumnsSet);		
		v = modelInput.get(0)[0][0];
		o = modelInput.get(1);//lsdeltac
		r = modelInput.get(2);//fsdeltac
		
		//build the master model	
		CGMasterModel masterModel = new CGMasterModel(initialColumnsSet,this.services.size(),this.DELTA, this.network.getLinkList().length,this.network.getVNFNb(), v, r, o,this.network.getLinksCapacities());		
		masterModel.buildMaster();

		//add configurations to the columns array in the master. The configuration need to be updated so o and r have the same timeslots as the updated delta
		//masterModel.columns.addAll(configurations);
		
		startlp = System.currentTimeMillis();
		while (true)
		{
			masterModel.runMasterModel(masterFilePath+"_"+masterIterations+".txt",masterIterations );

			for (int i = 0; i<services.size(); i++)
			{				
				pricingModelThread = new CGPricingModelThread(services.get(i), this.network, masterModel, this.DELTA,startTimeslot,pricingFilePath+"_"+services.get(i).getId()+"_"+pricingIterations+"_div.txt");
				pricingModelThread.buildPricingModel(null);
								
								
				//pricingResultPath = pricingFilePath+"_"+pricingIterations+"_"+services.get(i).getId()+"_"+masterModel.cut[services.get(i).getId()].getSize()+".txt";
				if(useIncumbentCallback)
				{
					incumbentCallback = new PricingIncumbentCallBackForThread(pricingModelThread, masterModel);
					pricingModelThread.cplex.use(incumbentCallback);
				}			  	 
			  	pricingModelThread.start();
			  	pricingThreads.add(pricingModelThread);
			}
			
			for (int i = 0; i<pricingThreads.size(); i++)
			{
				try {
					pricingModelThread = pricingThreads.get(i);
					//join all threads so we make sure that all threads are done execution before proceeding
					pricingModelThread.join();
					//get the reduced cost of the pricing 
					reducedCost = pricingModelThread.cplex.getObjValue();		
					
					if(useIncumbentCallback)
					{
						pricingModelThread.cplex.clearCallbacks();			
					}
					
					//compare to a very small value and not 0 since the reduced cost is double and may have value 0.0000 which will not be considered <=0
					if (reducedCost <=1e-10)
					{
						//if reduced cost<=0, no need to add column for this service
						//count the number of pricing having RC<=0, pricing for all services at the same iteration <=0 stop the CG
						pricingwithNegativeRC++;
						
						//this just to mention that no column was added to the service at this iteration so we can read easily the chosen column (if lambda[0][1] = 1; then pRes_0_1 column was chosen) 
						masterModel.cut[pricingModelThread.getService().getId()].add(null);
					}
					//make sure that the column is not already added to the master to reduce master execution time
					else if (!masterModel.columnExists(pricingModelThread.conf))
		        	{
						masterModel.addColumnToMaster(pricingModelThread.conf);
					}
					pricingModelThread.cplex.end();	
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			
			}			
			
			//empty the threads array for the next iteration or we will end up in an infinite loop
			pricingThreads = new ArrayList<CGPricingModelThread>() ;
			
			//optimal solution is reached
			if (pricingwithNegativeRC == services.size())
			{
				break;
			}
		
			pricingwithNegativeRC=0;
			pricingIterations++;
			masterIterations++;
		}	
		
		
		endlp = System.currentTimeMillis();
		results[0] = endlp - startlp;
		results[1] = masterModel.lpObjectiveValue;
		testResults.add(results);
		
		//add all incumbent solutions before running ILP; this will only add configurations that do not already exist in the master
		if(useIncumbentCallback)
		{
			masterModel.addColumnsToMaster(masterModel.incumbentColumns);
		}
		
		if(useHeuristicDiversification)
		{
			ArrayList<Configuration> diversificationConfigurations = sh.generateDiversificationConfigurations(services, this.DELTA, startTimeslot,nbOfConfigPerService );
			masterModel.addColumnsToMaster(diversificationConfigurations);			
		}		

		
		//run ILP after adding incumbent columns and add results to the array	
		startIlp = System.currentTimeMillis();
		masterModel.runILPMasterModel(ilpFilePath);
		endIlp = System.currentTimeMillis();
		
		results = new double[2];
		results[0] = endIlp - startIlp;
		results[1] = masterModel.IlpObjectiveValue;
		testResults.add(results);
		
		results = new double[2];
		results[0] = pricingIterations;
		results[1] = this.DELTA;
		testResults.add(results);
		
		masterModel.cplex.end();
		
		return testResults;
	}
	
	
	/*
	 * helper function that returns the number of times a certain value is mentionned in the array
	 */
	public int contains(int [] a, int b)
	{
		int count =0;
		for (int i = 0; i<a.length; i++)
		{
			if (a[i] ==b)
			{
				count++;
			}
		}
		return count;
	}
	
	public static void main(String[]args) throws IloException, IOException
	{	
		String pricingFilePath = "testResults/pricingResults/pRes_";//"pricingResults/pRes_"+this.service.getId()+"_"+iterations+".txt";
		String masterFilePath = "testResults/masterResults/mRes_";// "masterResults/mRes_"+iterations+".txt";
		String ilpFilePath ="testResults/masterResults/ILP.txt";
		ArrayList<double[]> CGResults = new ArrayList<double[]>();
		ArrayList<double[]> CGDiverResults = new ArrayList<double[]>();
		ArrayList<double[]> CGDiverResults2 = new ArrayList<double[]>();
		int delta =500;	//100
		
		//Create the network
		int vnfTypesNb= 5;//7,10;
		//Network  graph = new Network(vnfTypesNb,vnfTypesNb,4,5,200,200,1);
		//Network  graph = new Network(10,5,8,8,700,700,1,3);
		
		Network  graph = new Network(vnfTypesNb,vnfTypesNb,4,5,500,500,1,0);//the problem
		ArrayList<int[][]> physicalNetwork = graph.buildPhysicalNetwork();			 
		
		 //get the types of VNFs in the network
		int[] tf = physicalNetwork.get(2)[0];	
		
		 //generate services
		ServicesDriver driver = new ServicesDriver (3,  delta, 3, 5, 200, 500, 5 , 1, 500, 1500,tf);//the problem
		//ServicesDriver driver = new ServicesDriver (4,  delta, 3, 4, 50, 100, 4 , 1, 100, 200,tf);
		//ServicesDriver driver = new ServicesDriver (15, delta, 3, 7, 50, 100, 6 , 1, 100, 200,tf);
		ArrayList<int[][]>  services = driver.generateServicesForModel();
		ArrayList<Service> servicesObjects = driver.convertGeneratedServices(services);

			
		//start column generation
		ColumnGeneration CG = new ColumnGeneration(graph, servicesObjects, delta);		
		CGResults = CG.executeColumnGeneration(false,masterFilePath, ilpFilePath, pricingFilePath, false);
		CGDiverResults = CG.executeColumnGenerationDiversification(false, true, 0,3,masterFilePath, ilpFilePath, pricingFilePath);
		//CGDiverResults2 = CG.executeColumnGenerationDiversification(true, false, 0,0,masterFilePath, ilpFilePath, pricingFilePath);
		//print results
		/*if (CGResults==null||CGDiverResults == null || CGDiverResults2==null)
		{
			System.out.println("Some Services were not scheduled/mapped/routed - Column generation did not execute");
		}
		else
		{*/
			System.out.println("==============COLUMN GENERATION RESULTS==================");
			System.out.println("COLUMN GENERATION LP RESULTS");
			System.out.println("\t Execution Time: "+CGResults.get(0)[0]);
			System.out.println("\t Objective value: "+CGResults.get(0)[1]);
			
			System.out.println("\nCOLUMN GENERATION ILP RESULTS");
			System.out.println("\t Execution Time: "+CGResults.get(1)[0]);
			System.out.println("\t Objective value: "+CGResults.get(1)[1]);
			
			System.out.println("\nOther Info");
			System.out.println("\t Number of iterations needed to converge: "+CGResults.get(2)[0]);
			System.out.println("\t Timeline Delta: "+CGResults.get(2)[1]);
			
		System.out.println("\n==============COLUMN GENERATION with Diversification RESULTS -Heuristic ==================");
			System.out.println("COLUMN GENERATION LP RESULTS");
			System.out.println("\t Execution Time: "+CGDiverResults.get(0)[0]);
			System.out.println("\t Objective value: "+CGDiverResults.get(0)[1]);
			
			System.out.println("\nCOLUMN GENERATION ILP RESULTS");
			System.out.println("\t Execution Time: "+CGDiverResults.get(1)[0]);
			System.out.println("\t Objective value: "+CGDiverResults.get(1)[1]);
			
			System.out.println("\nOther Info");
			System.out.println("\t Number of iterations needed to converge: "+CGDiverResults.get(2)[0]);
			System.out.println("\t Timeline Delta: "+CGDiverResults.get(2)[1]);
			
			/*	System.out.println("\n==============COLUMN GENERATION with Diversification RESULTS -Incumbent ==================");
			System.out.println("COLUMN GENERATION LP RESULTS");
			System.out.println("\t Execution Time: "+CGDiverResults2.get(0)[0]);
			System.out.println("\t Objective value: "+CGDiverResults2.get(0)[1]);
			
			System.out.println("\nCOLUMN GENERATION ILP RESULTS");
			System.out.println("\t Execution Time: "+CGDiverResults2.get(1)[0]);
			System.out.println("\t Objective value: "+CGDiverResults2.get(1)[1]);
			
			System.out.println("\nOther Info");
			System.out.println("\t Number of iterations needed to converge: "+CGDiverResults2.get(2)[0]);
			System.out.println("\t Timeline Delta: "+CGDiverResults2.get(2)[1]);*/
		//}
		
	}
}
