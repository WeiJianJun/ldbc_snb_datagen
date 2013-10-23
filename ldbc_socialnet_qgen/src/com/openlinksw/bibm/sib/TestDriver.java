/*
 *  Big Database Semantic Metric Tools
 *
 * Copyright (C) 2011-2013 OpenLink Software <bdsmt@openlinksw.com>
 * All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation;  only Version 2 of the License dated
 * June 1991.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.openlinksw.bibm.sib;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.apache.log4j.xml.DOMConfigurator;

import umontreal.iro.lecuyer.util.PrintfFormat;

import benchmark.qualification.QueryResult;

import com.openlinksw.bibm.AbstractQueryResult;
import com.openlinksw.bibm.AbstractTestDriver;
import com.openlinksw.bibm.Query;
import com.openlinksw.bibm.Exceptions.BadSetupException;
import com.openlinksw.bibm.Exceptions.ExceptionException;
import com.openlinksw.bibm.Exceptions.RequestFailedException;
import com.openlinksw.bibm.qualification.Comparator;
import com.openlinksw.bibm.qualification.ResultCollector;
import com.openlinksw.bibm.qualification.ResultDescription;
import com.openlinksw.bibm.statistics.AbstractQueryMixStatistics;
import com.openlinksw.util.DoubleLogger;
import com.openlinksw.util.FiniteQueue;

public class TestDriver extends AbstractTestDriver {
    public IntegerOption nrRuns=new IntegerOption("runs <number of query mix runs>", null
            , "default: the number of clients");
    
    MultiStringOption querymixDirNames=new MultiStringOption("uc <use case query mix directory>"
            ,"Directly specifies a query mix directory."
            ,"Can be used several times, and with -ucf optionDirectly specifies a query mix directory.");
    
    BooleanOption randomProfileViewQuery = new BooleanOption("rpvq", "trigger the profile view query from the specified directory for some of the returned persons");
    
    public IntegerOption warmups=new IntegerOption("w <number of warm up runs before actual measuring>", 0,
        "default: 0");
    public BooleanOption warmUpdate=new BooleanOption("wud"
            ,"allow updates during warmups");
    public BooleanOption printQueriesBeforeRuns=new BooleanOption("pq"
            ,"print queries before runs");
    public BooleanOption profileViewQuery=new BooleanOption("pvq"
            ,"trigger the profile view query for returned persons");
    
       
    FileOption resourceDir=new FileOption("idir <data input directory>", "td_data"
                ,"The input directory for the Test Driver data"
                , "default: %%");
    FileOption updateFile=new FileOption("udataset <update dataset file name>", null
            ,"Specified an update file generated by the SIB dataset generator.");
    
    FileOption usecaseFile=new FileOption("ucf <use case file name>", null
            ,"Specifies where the use case description file can be found.");
        
    public File[] querymixDirs;
    public File querymixProfileViewDir;
    public SIBQueryMix queryMix;// The Benchmark Querymix
    public SIBQueryMix queryMixProfileView;
   
    public TestDriver(String args[]) throws IOException {
        super(version,
                "Usage: com.openlinksw.bibm.SIB.TestDriver <options> endpoints...", // CHECK package
                 "endpoint: The URL of the HTTP SPARQL or SQL endpoint");
        super.processProgramParameters(args);
        if (nrRuns.getValue()==null) {
            nrRuns.setValue(nrThreads.getValue());
        }
        
        // create ParameterPool
        boolean doSQL=this.doSQL.getValue();
        long seed = this.seed.getValue();
        File resourceDirectory = resourceDir.getValue();
        if (!resourceDirectory.exists() || !resourceDirectory.isDirectory()) {
            DoubleLogger.getErr().println(resourceDirectory.getAbsolutePath()+" does not exists or is not a directory.");
            DoubleLogger.getErr().println("Set Testdriver Data Directory with -idir option.");
            System.exit(1);
        }
        if (doSQL) {
            parameterPool = new SQLParameterPool(resourceDirectory, seed);
        } else  if (updateFile.getValue() == null) {
                parameterPool = new LocalSPARQLParameterPool(resourceDirectory, seed);
            } else {
                parameterPool = new LocalSPARQLParameterPool(resourceDirectory, seed, updateFile.getValue());
        }

        // Read which query mixes (directories) are used in the use case
        List<String> querymixDirNames=this.querymixDirNames.getValue();
        File usecaseFile = this.usecaseFile.getValue();
        if (usecaseFile!=null) {
            addUseCaseQuerymixes(querymixDirNames, usecaseFile);             
        }

        // read query mixes
        this.querymixDirs=new File[querymixDirNames.size()];
        int k=0;
        for (String uscaseDirname: querymixDirNames) {
            this.querymixDirs[k++] = new File(uscaseDirname);
        }
        SIBQueryMix mix0=null;
        //Long seedLoc = drillDown.getValue()?seed:null;
        Long seedLoc = null;
        for (File uscaseDir: querymixDirs) {
            if (!uscaseDir.exists()) {
                throw new BadSetupException("no such usecase directory: "+uscaseDir);
            }
            SIBQueryMix mix = new SIBQueryMix(parameterPool, uscaseDir, seedLoc);
            if (mix0==null) {
                mix0=mix;
            } else {
                mix0.append(mix);
            }
        }
        if (mix0.getQueries().size()==0) {
            throw new BadSetupException("no query files found");
        }
        queryMix=mix0;
        
        if (randomProfileViewQuery.getValue()) {
        	querymixProfileViewDir = new File(querymixDirs[0] + "/profile_view");
	        if (!querymixProfileViewDir.exists()) {
	            throw new BadSetupException("no such usecase directory: "+ querymixProfileViewDir);
	        }
	        SIBQueryMix mix1 = new SIBQueryMix(parameterPool, querymixProfileViewDir, seedLoc);
	        if (mix1.getQueries().size()==0) {
	            throw new BadSetupException("no query files for profile view found");
	        }
	        queryMixProfileView=mix1;
        }
  }

    /*
     * Read which query mixes (directories) are used in the use case
     */
    private void addUseCaseQuerymixes(List<String> querymixDirs, File usecaseFile) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(usecaseFile));
            String line = null;
            while ((line = reader.readLine()) != null) {
                String[] querymixInfo = line.split("=");
                if (querymixInfo.length != 2) {
                    throw new BadSetupException("Invalid entry in use case file " + usecaseFile + ":\n");
                }
                if (querymixInfo[0].toLowerCase().equals("querymix"))
                    querymixDirs.add(querymixInfo[1]);
            }

        } catch (IOException e) {
            throw new ExceptionException("Error when opening or processing usecase file: "+usecaseFile.getAbsolutePath(), e);
        }
    }

    //****** runtime support *******//
    public FiniteQueue<AbstractQueryResult> resultQueue=new FiniteQueue<AbstractQueryResult>(new LinkedList<AbstractQueryResult>()); 
    protected Comparator cmp;
    protected ResultCollector collector;
    protected FileWriter logger = new FileWriter("run.log");

    @Override
    public Collection<Query> getQueries() {
        return queryMix.getQueries().values();
    }
   
    public void run() throws Exception {
        ClientManager manager = new ClientManager(this);

        Thread managerThread = new Thread(manager);
        managerThread.start();

        File qualificationCompareFile = this.qualificationCompareFile.getValue();
        if  (qualificationCompareFile!=null) {
            cmp=new Comparator(qualificationCompareFile);
        }
        if  (qualOutFile!=null) {
            collector=new ResultCollector(qualOutFile);
            collector.addResultDescriptionsFromDriver(this);
       }

        ClientManager manager1 = new ClientManager(this);
		manager1.setNrRuns(0);
		manager1.setQueryMix(this.queryMixProfileView);
        
        for (;;) {
            AbstractQueryResult result = resultQueue.take();
            if (result==null) {
                // finish
                break;
            }
            Random r =  new Random(System.currentTimeMillis());
            com.openlinksw.bibm.qualification.QueryResult qr = result.getQueryResult();
            int index = qr.getHeader().indexOf(result.getQuery().getQuery().getPersonURIString());
            if (index != -1) {
            	result.getQuery().getQueryTypeSequence();
            	for (int i = 0; i < qr.getResultCount(); i++) {
            		if (r.nextInt(10) == 0) {
            			manager1.setNrRuns(manager1.getNrRuns() + 1);
            			((LocalSPARQLParameterPool)(manager1.driver.parameterPool)).addPeopleURI(qr.getResults().get(i).get(index));
            		}
            	}
            }
            if (collector != null) {
                collector.addResult(result.getQueryResult());
            }
            if (printResults.getValue()) {
                result.logResultInfo(logger);
             }
            if (cmp!=null) {
                cmp.addQueryResult(result.getQueryResult());
            }
        }
        
        Thread managerThread1 = new Thread(manager1);
        if (this.randomProfileViewQuery.getValue()) {
	        managerThread1.start();
	        
	        Thread.sleep(1000);
	        
	        for (;;) {
	            AbstractQueryResult result = resultQueue.take();
	            if (result==null) {
	                // finish
	                break;
	            }
	            if (printResults.getValue()) {
	                result.logResultInfo(logger);
	             }
	        }
        }

        AbstractQueryMixStatistics queryMixStat = manager.getQueryMixStat();
        logger.append(queryMixStat.getResultString());
        logger.flush();
        printXML(queryMixStat);
        if (cmp!=null) {
            cmp.reportTotal();
        }
        
        if (this.randomProfileViewQuery.getValue()) {
		    AbstractQueryMixStatistics queryMixStat1 = manager1.getQueryMixStat();
		    logger.append(queryMixStat1.getResultString());
		    logger.flush();
		    printXML(queryMixStat1);
        }
    }

    protected void testDriverShutdown() {
        if (collector != null) {
            try {
                collector.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    public static void main(String argv[]) throws InterruptedException {
        DOMConfigurator.configureAndWatch("log4j.xml", 60 * 1000);
        TestDriver testDriver = null;
        boolean printST=false; //  turn on for debugging
        try {
            testDriver = new TestDriver(argv);
            DoubleLogger.getOut().println("\nStarting test...\n");
            testDriver.run();
        } catch (ExceptionException e) {
            DoubleLogger.getErr().println(e.getMessages());
            if (printST || e.isPrintStack()) {
                 e.getCause().printStackTrace();
            }
        } catch (BadSetupException e) {
            DoubleLogger.getErr().println(e.getMessage());
            if (printST || e.isPrintStack()) {
                 e.printStackTrace();
            }
        } catch (RequestFailedException e) {
            if (e.getMessage()!=null) {
                DoubleLogger.getErr().println("Request failed: ", e.getMessage()).flush();              
            }
            if (printST) {
                 e.printStackTrace();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            if (testDriver==null) return;
            testDriver.testDriverShutdown();
        }
    }

}
