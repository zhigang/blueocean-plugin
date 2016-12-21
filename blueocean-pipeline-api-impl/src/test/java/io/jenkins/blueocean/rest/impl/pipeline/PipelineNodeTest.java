package io.jenkins.blueocean.rest.impl.pipeline;

import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * @author Vivek Pandey
 */
public class PipelineNodeTest extends PipelineBaseTest {

    //TODO: Enable this test if there is way to determine when test starts running and not waiting till launched
//    @Test
    public void nodesTest1() throws IOException, ExecutionException, InterruptedException {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "p1");
        job.setDefinition(new CpsFlowDefinition("node {\n" +
            "   stage 'Stage 1a'\n" +
            "    echo 'Stage 1a'\n" +
            "\n" +
            "   stage 'Stage 2'\n" +
            "   echo 'Stage 2'\n" +
            "}\n" +
            "node {\n" +
            "    stage 'testing'\n" +
            "    echo 'testig'\n" +
            "}\n" +
            "\n" +
            "node {\n" +
            "    parallel firstBranch: {\n" +
            "    echo 'first Branch'\n" +
            "    sh 'sleep 1'\n" +
            "    echo 'first Branch end'\n" +
            "    }, secondBranch: {\n" +
            "       echo 'Hello second Branch'\n" +
            "    sh 'sleep 1'   \n" +
            "    echo 'second Branch end'\n" +
            "       \n" +
            "    },\n" +
            "    failFast: false\n" +
            "}"));
        job.scheduleBuild2(0).waitForStart();

        Thread.sleep(1000);
        List<Map> resp = get("/organizations/jenkins/pipelines/p1/runs/1/nodes/", List.class);

        for(int i=0; i< resp.size();i++){
            Map rn = resp.get(i);
            List<Map> edges = (List<Map>) rn.get("edges");

            if(rn.get("displayName").equals("Stage 1a")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("Stage 2")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("testing")){
                Assert.assertEquals(2, edges.size());
                Assert.assertEquals(rn.get("result"), "UNKNOWN");
                Assert.assertEquals(rn.get("state"), "RUNNING");
            }else if(rn.get("displayName").equals("firstBranch")){
                Assert.assertEquals(0, edges.size());
                Assert.assertEquals(rn.get("result"), "UNKNOWN");
                Assert.assertEquals(rn.get("state"), "RUNNING");
            }else if(rn.get("displayName").equals("secondBranch")){
                Assert.assertEquals(0, edges.size());
                Assert.assertEquals(rn.get("result"), "UNKNOWN");
                Assert.assertEquals(rn.get("state"), "RUNNING");
            }
        }
    }

    //JENKINS-39203
    @Test
    public void stepStatusForUnstableBuild() throws Exception{
        String p = "node {\n" +
                "   echo 'Hello World'\n" +
                "   try{\n" +
                "    echo 'Inside try'\n" +
                "   }finally{\n" +
                "    sh 'echo \"blah\"' \n" +
                "    currentBuild.result = \"UNSTABLE\"\n" +
                "   }\n" +
                "}";

        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");

        job1.setDefinition(new CpsFlowDefinition(p));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.UNSTABLE,b1);

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/", List.class);
        Assert.assertEquals(resp.size(),3);

        for(int i=0; i< resp.size();i++) {
            Map rn = resp.get(i);
            Assert.assertEquals(rn.get("result"), "SUCCESS");
            Assert.assertEquals(rn.get("state"), "FINISHED");
        }

    }

    //JENKINS-39296
    @Test
    public void stepStatusForFailedBuild() throws Exception{
        String p = "node {\n" +
                "   echo 'Hello World'\n" +
                "   try{\n" +
                "    echo 'Inside try'\n" +
                "    sh 'this should fail'" +
                "   }finally{\n" +
                "    sh 'echo \"blah\"' \n" +
                "   }\n" +
                "}";

        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");

        job1.setDefinition(new CpsFlowDefinition(p));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE,b1);

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/", List.class);
        Assert.assertEquals(resp.size(),4);

        for(int i=0; i< resp.size();i++) {
            Map rn = resp.get(i);
            if(i==2){
                Assert.assertEquals("FAILURE", rn.get("result"));
            }else {
                Assert.assertEquals("SUCCESS", rn.get("result"));
            }
            Assert.assertEquals("FINISHED", rn.get("state"));
        }

    }

    @Test
    public void testBlockStage() throws Exception{
        String pipeline = "" +
            "node {" +
            "   stage ('dev');" +                 //start
            "     echo ('development'); " +

            "   stage ('Build') { " +
            "     echo ('Building'); " +
            "   } \n" +
            "   stage ('test') { " +
            "     echo ('Testing'); " +
            "     parallel firstBranch: {\n" + //1
            "       echo 'first Branch'\n" +
            "       sh 'sleep 1'\n" +
            "       echo 'first Branch end'\n" +
            "     }, secondBranch: {\n" +
            "       echo 'Hello second Branch'\n" +
            "       sh 'sleep 1'   \n" +
            "       echo 'second Branch end'\n" +
            "       \n" +
            "    },\n" +
            "    failFast: false\n" +
            "   } \n" +
            "   stage ('deploy') { " +
            "     writeFile file: 'file.txt', text:'content'; " +
            "     archive(includes: 'file.txt'); " +
            "     echo ('Deploying'); " +
            "   } \n" +
            "}";


        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");

        job1.setDefinition(new CpsFlowDefinition(pipeline));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);

        PipelineNodeGraphBuilder builder = new PipelineNodeGraphBuilder(b1);

        List<FlowNode> stages = builder.getSages();
        List<FlowNode> parallels = builder.getParallelBranches();

        Assert.assertEquals(4, stages.size());
        Assert.assertEquals(2, parallels.size());

        //TODO: complete test
        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(6, resp.size());

        String testStageId=null;

        for(int i=0; i< resp.size();i++){
            Map rn = resp.get(i);
            List<Map> edges = (List<Map>) rn.get("edges");

            if(rn.get("displayName").equals("dev")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("build")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("test")){
                testStageId = (String) rn.get("id");
                Assert.assertEquals(2, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("firstBranch")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("secondBranch")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("deploy")){
                Assert.assertEquals(0, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }
        }

        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/", List.class);
        Assert.assertEquals(12,resp.size());


        Assert.assertNotNull(testStageId);
        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+testStageId+"/steps/", List.class);
        Assert.assertEquals(7,resp.size());

    }

    @Test
    public void testNonblockStageSteps() throws Exception{
        String pipeline = "node {\n" +
            "  stage 'Checkout'\n" +
            "      echo 'checkingout'\n" +
            "  stage 'Build'\n" +
            "      echo 'building'\n" +
            "  stage 'Archive'\n" +
            "      echo 'archiving...'\n" +
            "}";


        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");

        job1.setDefinition(new CpsFlowDefinition(pipeline));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);


        NodeGraphBuilder builder = NodeGraphBuilder.NodeGraphBuilderFactory.getInstance(b1);
        List<FlowNode> stages = getStages(builder);
        List<FlowNode> parallels = getParallelNodes(builder);

        Assert.assertEquals(3, stages.size());
        Assert.assertEquals(0, parallels.size());

        //TODO: complete test
        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(3, resp.size());

        String checkoutId = (String) resp.get(0).get("id");
        String buildId = (String) resp.get(0).get("id");
        String archiveId = (String) resp.get(0).get("id");

        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/", List.class);
        Assert.assertEquals(3,resp.size());


        Assert.assertNotNull(checkoutId);
        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+checkoutId+"/steps/", List.class);
        Assert.assertEquals(1,resp.size());


        Assert.assertNotNull(buildId);
        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+buildId+"/steps/", List.class);
        Assert.assertEquals(1,resp.size());


        Assert.assertNotNull(archiveId);
        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+archiveId+"/steps/", List.class);
        Assert.assertEquals(1,resp.size());
    }


    @Test
    public void testNestedBlockStage() throws Exception{
        String pipeline = "" +
            "node {" +
            "   stage ('dev');" +                 //start
            "     echo ('development'); " +
            "   stage ('Build') { " +
            "     echo ('Building'); " +
            "     stage('Packaging') {" +
            "         echo 'packaging...'" +
            "     }" +
            "   } \n" +
            "   stage ('test') { " +
            "     echo ('Testing'); " +
            "     parallel firstBranch: {\n" +
            "       echo 'Hello first Branch'\n" +
            "       echo 'first Branch 1'\n" +
            "       echo 'first Branch end'\n" +
            "       \n" +
            "    },\n" +
            "    thirdBranch: {\n" +
            "       echo 'Hello third Branch'\n" +
            "       sh 'sleep 1'   \n" +
            "       echo 'third Branch 1'\n" +
            "       echo 'third Branch 2'\n" +
            "       echo 'third Branch end'\n" +
            "       \n" +
            "    },\n" +
            "    secondBranch: {"+
            "       echo 'first Branch'\n" +
                "     stage('firstBranchTest') {"+
                "       echo 'running firstBranchTest'\n" +
                "       sh 'sleep 1'\n" +
                "     }\n"+
                "       echo 'first Branch end'\n" +
                "     },\n"+
            "    failFast: false\n" +
            "   } \n" +
            "   stage ('deploy') { " +
            "     writeFile file: 'file.txt', text:'content'; " +
            "     archive(includes: 'file.txt'); " +
            "     echo ('Deploying'); " +
            "   } \n" +
            "}";


        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");

        job1.setDefinition(new CpsFlowDefinition(pipeline));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);


        NodeGraphBuilder builder = NodeGraphBuilder.NodeGraphBuilderFactory.getInstance(b1);
        List<FlowNode> stages = getStages(builder);
        List<FlowNode> parallels = getParallelNodes(builder);

        Assert.assertEquals(4, stages.size());
        Assert.assertEquals(3, parallels.size());

        //TODO: complete test
        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(7, resp.size());

        String testStageId=null;

        String devNodeId = null;
        for(int i=0; i< resp.size();i++){
            Map rn = resp.get(i);
            List<Map> edges = (List<Map>) rn.get("edges");

            if(rn.get("displayName").equals("dev")){
                Assert.assertEquals(0, i);
                devNodeId = (String) rn.get("id");
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("build")){
                Assert.assertEquals(1, i);
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("test")){
                Assert.assertEquals(2, i);
                testStageId = (String) rn.get("id");
                Assert.assertEquals(3, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("firstBranch")){
                Assert.assertEquals(3, i);
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("secondBranch")){
                Assert.assertEquals(4, i);
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("thirdBranch")){
                Assert.assertEquals(5, i);
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("deploy")){
                Assert.assertEquals(6, i);
                Assert.assertEquals(0, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }
        }

        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/", List.class);
        Assert.assertEquals(19,resp.size());


        Assert.assertNotNull(testStageId);
        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+testStageId+"/steps/", List.class);
        Assert.assertEquals(13,resp.size());

        //firstBranch is parallel with nested stage. firstBranch /steps should also include steps inside nested stage
        FlowNode firstBranch=null;
        FlowNode secondBranch=null;
        FlowNode thirdBranch=null;
        for(FlowNode n: parallels){
            if(n.getDisplayName().equals("Branch: firstBranch")){
                firstBranch = n;
            }
            if(n.getDisplayName().equals("Branch: secondBranch")){
                secondBranch = n;
            }
            if(n.getDisplayName().equals("Branch: thirdBranch")){
                thirdBranch = n;
            }
        }
        Assert.assertNotNull(firstBranch);
        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+firstBranch.getId()+"/steps/", List.class);
        Assert.assertEquals(3,resp.size());

        Assert.assertNotNull(secondBranch);
        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+secondBranch.getId()+"/steps/", List.class);
        Assert.assertEquals(4,resp.size());

        Assert.assertNotNull(thirdBranch);
        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+thirdBranch.getId()+"/steps/", List.class);
        Assert.assertEquals(5,resp.size());

        Assert.assertNotNull(devNodeId);
        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+devNodeId+"/steps/", List.class);
        Assert.assertEquals(1,resp.size());

    }

    @Test
    public void nodesWithFutureTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition("node {\n" +
            "  stage 'build'\n" +
            "  sh 'echo s1'\n" +
            "  stage 'test'\n" +
            "  echo 'Hello World 2'\n" +
            "}"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.SUCCESS,b1);

        get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);

        job1.setDefinition(new CpsFlowDefinition("node {\n" +
            "  stage 'build'\n" +
            "  sh 'echo s1'\n" +
            "  stage 'test'\n" +
            "  echo 'Hello World 2'\n" +
            "}\n" +
            "parallel firstBranch: {\n" +
            "  echo 'Hello first'\n" +
            "}, secondBranch: {\n" +
            " echo 'Hello second'\n" +
            "}"));



        WorkflowRun b2 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.SUCCESS,b2);

        job1.setDefinition(new CpsFlowDefinition("node {\n" +
            "  stage 'build'\n" +
            "  sh 'echo s1'\n" +
            "  stage 'test'\n" +
            "  echo 'Hello World 2'\n" +
            "}\n" +
            "parallel firstBranch: {\n" +
            "  echo 'Hello first'\n" +
            "}, secondBranch: {\n" +
            " sh 'Hello second'\n" +
            "}"));



        WorkflowRun b3 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE,b3);

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(2, resp.size());
    }

    @Test
    public void nodesWithPartialParallels() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");

        job1.setDefinition(new CpsFlowDefinition("node {\n" +
            "    stage \"hey\"\n" +
            "    sh \"echo yeah\"\n" +
            "    \n" +
            "    stage \"par\"\n" +
            "    \n" +
            "    parallel left : {\n" +
            "            sh \"echo OMG BS\"\n" +
            "            sh \"echo yeah\"\n" +
            "        }, \n" +
            "        \n" +
            "        right : {\n" +
            "            sh \"echo wozzle\"\n" +
            "        }\n" +
            "    \n" +
            "    stage \"ho\"\n" +
            "        sh \"echo done\"\n" +
            "}"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        Thread.sleep(1000);

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);

        Assert.assertEquals(5, resp.size());

        job1.setDefinition(new CpsFlowDefinition("node {\n" +
            "    stage \"hey\"\n" +
            "    sh \"echo yeah\"\n" +
            "    \n" +
            "    stage \"par\"\n" +
            "    \n" +
            "    parallel left : {\n" +
            "            sh \"echo OMG BS\"\n" +
            "            echo \"running\"\n" +
            "            def branchInput = input message: 'Please input branch to test against', parameters: [[$class: 'StringParameterDefinition', defaultValue: 'master', description: '', name: 'branch']]\n" +
            "            echo \"BRANCH NAME: ${branchInput}\"\n" +
            "            sh \"echo yeah\"\n" +
            "        }, \n" +
            "        \n" +
            "        right : {\n" +
            "            sh \"echo wozzle\"\n" +
            "            def branchInput = input message: 'MF Please input branch to test against', parameters: [[$class: 'StringParameterDefinition', defaultValue: 'master', description: '', name: 'branch']]\n" +
            "            echo \"BRANCH NAME: ${branchInput}\"\n" +
            "        }\n" +
            "    \n" +
            "    stage \"ho\"\n" +
            "        sh \"echo done\"\n" +
            "}"));

        job1.scheduleBuild2(0);
        Thread.sleep(1000);

        resp = get("/organizations/jenkins/pipelines/pipeline1/runs/2/nodes/", List.class);

        Assert.assertEquals(5, resp.size());

        Map leftNode = resp.get(2);
        Assert.assertEquals("left", leftNode.get("displayName"));

        Map rightNode = resp.get(3);
        Assert.assertEquals("right", rightNode.get("displayName"));

        List<Map> leftSteps = get("/organizations/jenkins/pipelines/pipeline1/runs/2/nodes/"+leftNode.get("id")+"/steps/", List.class);

        Assert.assertEquals(3, leftSteps.size());

        List<Map> rightSteps = get("/organizations/jenkins/pipelines/pipeline1/runs/2/nodes/"+rightNode.get("id")+"/steps/", List.class);

        Assert.assertEquals(2, rightSteps.size());
    }


    @Test
    public void nodesTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition("stage \"Build\"\n" +
            "    node {\n" +
            "       sh \"echo here\"\n" +
            "    }\n" +
            "\n" +
            "stage \"Test\"\n" +
            "    parallel (\n" +
            "        \"Firefox\" : {\n" +
            "            node {\n" +
            "                sh \"echo ffox\"\n" +
            "            }\n" +
            "        },\n" +
            "        \"Chrome\" : {\n" +
            "            node {\n" +
            "                sh \"echo chrome\"\n" +
            "            }\n" +
            "        }\n" +
            "    )\n" +
            "\n" +
            "stage \"CrashyMcgee\"\n" +
            "  parallel (\n" +
            "    \"SlowButSuccess\" : {\n" +
            "        node {\n" +
            "            echo 'This is time well spent.'\n" +
            "        }\n" +
            "    },\n" +
            "    \"DelayThenFail\" : {\n" +
            "        node {\n" +
            "            echo 'Not yet.'\n" +
            "        }\n" +
            "    }\n" +
            "  )\n" +
            "\n" +
            "\n" +
            "stage \"Deploy\"\n" +
            "    node {\n" +
            "        sh \"echo deploying\"\n" +
            "    }"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);

        get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);

        job1.setDefinition(new CpsFlowDefinition("stage \"Build\"\n" +
            "    node {\n" +
            "       sh \"echo here\"\n" +
            "    }\n" +
            "\n" +
            "stage \"Test\"\n" +
            "    parallel (\n" +
            "        \"Firefox\" : {\n" +
            "            node {\n" +
            "                sh \"echo ffox\"\n" +
            "            }\n" +
            "        },\n" +
            "        \"Chrome\" : {\n" +
            "            node {\n" +
            "                sh \"echo chrome\"\n" +
            "            }\n" +
            "        }\n" +
            "    )\n" +
            "\n" +
            "stage \"CrashyMcgee\"\n" +
            "  parallel (\n" +
            "    \"SlowButSuccess\" : {\n" +
            "        node {\n" +
            "            echo 'This is time well spent.'\n" +
            "            sh 'sleep 3;'\n" +
            "        }\n" +
            "    },\n" +
            "    \"DelayThenFail\" : {\n" +
            "        node {\n" +
            "            echo 'Fail soon.'\n" +
            "            echo 'KABOOM!'\n" +
            "            sh '11exit 1'\n" +
            "        }\n" +
            "    }\n" +
            "  )\n" +
            "\n" +
            "\n" +
            "stage \"Deploy\"\n" +
            "    node {\n" +
            "        sh \"echo deploying\"\n" +
            "    }"));



        WorkflowRun b2 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE,b2);

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/2/nodes/", List.class);
        Assert.assertEquals(resp.size(), 8);
        for(int i=0; i< resp.size();i++){
            Map rn = resp.get(i);
            List<Map> edges = (List<Map>) rn.get("edges");

            if(rn.get("displayName").equals("Test")){
                Assert.assertEquals(2, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("Firefox")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("Chrome")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("CrashyMcgee")){
                Assert.assertEquals(2, edges.size());
                Assert.assertEquals(rn.get("result"), "FAILURE");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("SlowButSuccess")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("DelayThenFail")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "FAILURE");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("build")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(rn.get("result"), "SUCCESS");
                Assert.assertEquals(rn.get("state"), "FINISHED");
            }else if(rn.get("displayName").equals("Deploy")){
                Assert.assertEquals(0, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
            }

        }

    }

    @Test
    public void nodesFailureTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition("stage \"Build\"\n" +
            "    node {\n" +
            "       sh \"echo here\"\n" +
            "    }\n" +
            "\n" +
            "stage \"Test\"\n" +
            "    parallel (\n" +
            "        \"Firefox\" : {\n" +
            "            node {\n" +
            "                sh \"echo ffox\"\n" +
            "            }\n" +
            "        },\n" +
            "        \"Chrome\" : {\n" +
            "            node {\n" +
            "                sh \"echo chrome\"\n" +
            "            }\n" +
            "        }\n" +
            "    )\n" +
            "\n" +
            "stage \"CrashyMcgee\"\n" +
            "  parallel (\n" +
            "    \"SlowButSuccess\" : {\n" +
            "        node {\n" +
            "            echo 'This is time well spent.'\n" +
            "        }\n" +
            "    },\n" +
            "    \"DelayThenFail\" : {\n" +
            "        node {\n" +
            "            echo 'Not yet.'\n" +
            "        }\n" +
            "    }\n" +
            "  )\n" +
            "\n" +
            "\n" +
            "stage \"Deploy\"\n" +
            "    node {\n" +
            "        sh \"echo deploying\"\n" +
            "    }"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);

        job1.setDefinition(new CpsFlowDefinition("throw stage \"Build\"\n" +
            "    node {\n" +
            "       sh \"echo here\"\n" +
            "    }\n" +
            "\n" +
            "stage \"Test\"\n" +
            "    parallel (\n" +
            "        \"Firefox\" : {\n" +
            "            node {\n" +
            "                sh \"echo ffox\"\n" +
            "            }\n" +
            "        },\n" +
            "        \"Chrome\" : {\n" +
            "            node {\n" +
            "                sh \"echo chrome\"\n" +
            "            }\n" +
            "        }\n" +
            "    )\n" +
            "\n" +
            "stage \"CrashyMcgee\"\n" +
            "  parallel (\n" +
            "    \"SlowButSuccess\" : {\n" +
            "        node {\n" +
            "            echo 'This is time well spent.'\n" +
            "        }\n" +
            "    },\n" +
            "    \"DelayThenFail\" : {\n" +
            "        node {\n" +
            "            echo 'Not yet.'\n" +
            "        }\n" +
            "    }\n" +
            "  )\n" +
            "\n" +
            "\n" +
            "stage \"Deploy\"\n" +
            "    node {\n" +
            "        sh \"echo deploying\"\n" +
            "    }"));

        job1.scheduleBuild2(0);
        WorkflowRun b2 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, b2);

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/2/nodes/", List.class);
        Assert.assertEquals(8, resp.size());
        for(int i=0; i< resp.size();i++){
            Map rn = resp.get(i);
            List<Map> edges = (List<Map>) rn.get("edges");

            if(rn.get("displayName").equals("Test")){
                Assert.assertEquals(2, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
            }else if(rn.get("displayName").equals("Firefox")){
                Assert.assertEquals(1, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
            }else if(rn.get("displayName").equals("Chrome")){
                Assert.assertEquals(1, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
            }else if(rn.get("displayName").equals("CrashyMcgee")){
                Assert.assertEquals(2, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
            }else if(rn.get("displayName").equals("SlowButSuccess")){
                Assert.assertEquals(1, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
            }else if(rn.get("displayName").equals("DelayThenFail")){
                Assert.assertEquals(1, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
            }else if(rn.get("displayName").equals("build")){
                Assert.assertEquals(1, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
            }else if(rn.get("displayName").equals("Deploy")){
                Assert.assertEquals(0, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
            }

        }
    }


    @Test
    public void getPipelineJobRunNodesTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");


        job1.setDefinition(new CpsFlowDefinition("stage 'build'\n" +
            "node{\n" +
            "  echo \"Building...\"\n" +
            "}\n" +
            "\n" +
            "stage 'test'\n" +
            "parallel 'unit':{\n" +
            "  node{\n" +
            "    echo \"Unit testing...\"\n" +
            "  }\n" +
            "},'integration':{\n" +
            "  node{\n" +
            "    echo \"Integration testing...\"\n" +
            "  }\n" +
            "}, 'ui':{\n" +
            "  node{\n" +
            "    echo \"UI testing...\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "stage 'deploy'\n" +
            "node{\n" +
            "  echo \"Deploying\"\n" +
            "}" +
            "\n" +
            "stage 'deployToProd'\n" +
            "node{\n" +
            "  echo \"Deploying to production\"\n" +
            "}"
        ));
        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);

        NodeGraphBuilder builder = NodeGraphBuilder.NodeGraphBuilderFactory.getInstance(b1);

        List<FlowNode> nodes = getStagesAndParallels(builder);
        List<FlowNode> parallelNodes = getParallelNodes(builder);

        Assert.assertEquals(7, nodes.size());
        Assert.assertEquals(3, parallelNodes.size());

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);

        Assert.assertEquals(nodes.size(), resp.size());
        for(int i=0; i< nodes.size();i++){
            FlowNode n = nodes.get(i);
            Map rn = resp.get(i);
            Assert.assertEquals(n.getId(), rn.get("id"));
            Assert.assertEquals(getNodeName(n), rn.get("displayName"));
            Assert.assertEquals("SUCCESS", rn.get("result"));
            List<Map> edges = (List<Map>) rn.get("edges");


            Assert.assertTrue((int)rn.get("durationInMillis") > 0);
            if(n.getDisplayName().equals("test")){
                Assert.assertEquals(parallelNodes.size(), edges.size());
                Assert.assertEquals(edges.get(i).get("id"), parallelNodes.get(i).getId());
            }else if(n.getDisplayName().equals("build")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(edges.get(i).get("id"), nodes.get(i+1).getId());
            }else if(n.getDisplayName().equals("deploy")){
                Assert.assertEquals(1, edges.size());
            }else if(n.getDisplayName().equals("deployToProd")){
                Assert.assertEquals(0, edges.size());
            }else{
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(edges.get(0).get("id"), nodes.get(nodes.size() - 2).getId());
            }
        }
    }


    @Test
    public void getPipelineStepsTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");


        job1.setDefinition(new CpsFlowDefinition("stage 'build'\n" +
            "node{\n" +
            "  sh \"echo Building...\"\n" +
            "}\n" +
            "\n" +
            "stage 'test'\n" +
            "parallel 'unit':{\n" +
            "  node{\n" +
            "    echo \"Unit testing...\"\n" +
            "    sh \"echo Tests running\"\n" +
            "    sh \"echo Tests completed\"\n" +
            "  }\n" +
            "},'integration':{\n" +
            "  node{\n" +
            "    echo \"Integration testing...\"\n" +
            "  }\n" +
            "}, 'ui':{\n" +
            "  node{\n" +
            "    echo \"UI testing...\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "node{\n" +
            "  echo \"Done Testing\"\n" +
            "}" +
            "\n" +
            "stage 'deploy'\n" +
            "node{\n" +
            "  echo \"Deploying\"\n" +
            "}" +
            "\n" +
            "stage 'deployToProd'\n" +
            "node{\n" +
            "  echo \"Deploying to production\"\n" +
            "}"
        ));
        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);

        FlowGraphTable nodeGraphTable = new FlowGraphTable(b1.getExecution());
        nodeGraphTable.build();
        List<FlowNode> nodes = getStages(nodeGraphTable);
        List<FlowNode> parallelNodes = getParallelNodes(nodeGraphTable);

        Assert.assertEquals(7, nodes.size());
        Assert.assertEquals(3, parallelNodes.size());

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+nodes.get(1).getId()+"/steps/", List.class);
        Assert.assertEquals(6, resp.size());

        Map step = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+parallelNodes.get(0).getId()+"/steps/"+resp.get(0).get("id"), Map.class);

        assertNotNull(step);

        String stepLog = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+parallelNodes.get(0).getId()+"/steps/"+resp.get(0).get("id")+"/log", String.class);
        assertNotNull(stepLog);
    }

    @Test
    public void getPipelineWihNodesAllStepsTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");


        job1.setDefinition(new CpsFlowDefinition("stage 'build'\n" +
            "node{\n" +
            "  sh \"echo Building...\"\n" +
            "}\n" +
            "\n" +
            "stage 'test'\n" +
            "parallel 'unit':{\n" +
            "  node{\n" +
            "    echo \"Unit testing...\"\n" +
            "    sh \"echo Tests running\"\n" +
            "    sh \"echo Tests completed\"\n" +
            "  }\n" +
            "},'integration':{\n" +
            "  node{\n" +
            "    echo \"Integration testing...\"\n" +
            "  }\n" +
            "}, 'ui':{\n" +
            "  node{\n" +
            "    echo \"UI testing...\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "node{\n" +
            "  echo \"Done Testing\"\n" +
            "}" +
            "\n" +
            "stage 'deploy'\n" +
            "node{\n" +
            "  echo \"Deploying\"\n" +
            "}" +
            "\n" +
            "stage 'deployToProd'\n" +
            "node{\n" +
            "  echo \"Deploying to production\"\n" +
            "}"
        ));
        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);

        FlowGraphTable nodeGraphTable = new FlowGraphTable(b1.getExecution());
        nodeGraphTable.build();
        List<FlowNode> nodes = getStages(nodeGraphTable);
        List<FlowNode> parallelNodes = getParallelNodes(nodeGraphTable);

        Assert.assertEquals(7, nodes.size());
        Assert.assertEquals(3, parallelNodes.size());

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/", List.class);
        Assert.assertEquals(9,resp.size());
    }

    @Test
    public void getPipelineWihoutNodesAllStepsTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");


        job1.setDefinition(new CpsFlowDefinition(
            "node{\n" +
            "  sh \"echo Building...\"\n" +
            "}\n" +
            "  node{\n" +
            "    echo \"Unit testing...\"\n" +
            "    sh \"echo Tests running\"\n" +
            "    sh \"echo Tests completed\"\n" +
            "  }"
        ));
        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/", List.class);
        Assert.assertEquals(4,resp.size());
        String log = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/"+resp.get(0).get("id")+"/log/", String.class);
        assertNotNull(log);
    }



    @Test
    public void getPipelineJobRunNodesTestWithFuture() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");


        job1.setDefinition(new CpsFlowDefinition("stage 'build'\n" +
            "node{\n" +
            "  echo \"Building...\"\n" +
            "}\n" +
            "\n" +
            "stage 'test'\n" +
            "parallel 'unit':{\n" +
            "  node{\n" +
            "    echo \"Unit testing...\"\n" +
            "  }\n" +
            "},'integration':{\n" +
            "  node{\n" +
            "    echo \"Integration testing...\"\n" +
            "  }\n" +
            "}, 'ui':{\n" +
            "  node{\n" +
            "    echo \"UI testing...\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "stage 'deploy'\n" +
            "node{\n" +
            "  echo \"Deploying\"\n" +
            "}" +
            "\n" +
            "stage 'deployToProd'\n" +
            "node{\n" +
            "  echo \"Deploying to production\"\n" +
            "}"
        ));
        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);

        NodeGraphBuilder builder = NodeGraphBuilder.NodeGraphBuilderFactory.getInstance(b1);
        List<FlowNode> nodes = getStagesAndParallels(builder);
        List<FlowNode> parallelNodes = getParallelNodes(builder);

        Assert.assertEquals(7, nodes.size());
        Assert.assertEquals(3, parallelNodes.size());

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);

        Assert.assertEquals(nodes.size(), resp.size());
        for(int i=0; i< nodes.size();i++){
            FlowNode n = nodes.get(i);
            Map rn = resp.get(i);
            Assert.assertEquals(n.getId(), rn.get("id"));
            Assert.assertEquals(getNodeName(n), rn.get("displayName"));
            Assert.assertEquals("SUCCESS", rn.get("result"));
            List<Map> edges = (List<Map>) rn.get("edges");

            if(n.getDisplayName().equals("test")){
                Assert.assertEquals(parallelNodes.size(), edges.size());
                Assert.assertEquals(edges.get(i).get("id"), parallelNodes.get(i).getId());
            }else if(n.getDisplayName().equals("build")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(edges.get(i).get("id"), nodes.get(i+1).getId());
            }else if(n.getDisplayName().equals("deploy")){
                Assert.assertEquals(1, edges.size());
            }else if(n.getDisplayName().equals("deployToProd")){
                Assert.assertEquals(0, edges.size());
            }else{
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(edges.get(0).get("id"), nodes.get(nodes.size() - 2).getId());
            }
        }

        job1.setDefinition(new CpsFlowDefinition("stage 'build'\n" +
            "node{\n" +
            "  echo \"Building...\"\n" +
            "}\n" +
            "\n" +
            "stage 'test'\n" +
            "parallel 'unit':{\n" +
            "  node{\n" +
            "    echo \"Unit testing...\"\n" +
            "    sh \"`fail-the-build`\"\n" + //fail the build intentionally
            "  }\n" +
            "},'integration':{\n" +
            "  node{\n" +
            "    echo \"Integration testing...\"\n" +
            "  }\n" +
            "}, 'ui':{\n" +
            "  node{\n" +
            "    echo \"UI testing...\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "stage 'deploy'\n" +
            "node{\n" +
            "  echo \"Deploying\"\n" +
            "}" +
            "\n" +
            "stage 'deployToProd'\n" +
            "node{\n" +
            "  echo \"Deploying to production\"\n" +
            "}"
        ));
        b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE,b1);
        resp = get(String.format("/organizations/jenkins/pipelines/pipeline1/runs/%s/nodes/",b1.getId()), List.class);
        Assert.assertEquals(nodes.size(), resp.size());
        for(int i=0; i< nodes.size();i++){
            FlowNode n = nodes.get(i);
            Map rn = resp.get(i);
            Assert.assertEquals(n.getId(), rn.get("id"));
            Assert.assertEquals(getNodeName(n), rn.get("displayName"));
            List<Map> edges = (List<Map>) rn.get("edges");
            if(n.getDisplayName().equals("build")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(edges.get(i).get("id"), nodes.get(i+1).getId());
                Assert.assertEquals("SUCCESS", rn.get("result"));
                Assert.assertEquals("FINISHED", rn.get("state"));
            }else if (n.getDisplayName().equals("test")){
                Assert.assertEquals(parallelNodes.size(), edges.size());
                Assert.assertEquals(edges.get(i).get("id"), parallelNodes.get(i).getId());
                Assert.assertEquals("FAILURE", rn.get("result"));
                Assert.assertEquals("FINISHED", rn.get("state"));
            }else if(PipelineNodeUtil.getDisplayName(n).equals("unit")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(edges.get(0).get("id"), nodes.get(nodes.size() - 2).getId());
                Assert.assertEquals("FAILURE", rn.get("result"));
                Assert.assertEquals("FINISHED", rn.get("state"));
            }else if(n.getDisplayName().equals("deploy")){
                Assert.assertEquals(1, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
                Assert.assertNull(rn.get("startTime"));
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(edges.get(0).get("id"), nodes.get(nodes.size() - 1).getId());
            }else if(n.getDisplayName().equals("deployToProd")){
                Assert.assertEquals(0, edges.size());
                Assert.assertNull(rn.get("result"));
                Assert.assertNull(rn.get("state"));
                Assert.assertNull(rn.get("startTime"));
                Assert.assertEquals(0, edges.size());
            }else{
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(edges.get(0).get("id"), nodes.get(nodes.size() - 2).getId());
                Assert.assertEquals("SUCCESS", rn.get("result"));
                Assert.assertEquals("FINISHED", rn.get("state"));
            }
        }

    }

    @Test
    public void getPipelineJobRunNodesWithFailureTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");


        job1.setDefinition(new CpsFlowDefinition("stage 'build'\n" +
            "node{\n" +
            "  echo \"Building...\"\n" +
            "}\n" +
            "\n" +
            "stage 'test'\n" +
            "parallel 'unit':{\n" +
            "  node{\n" +
            "    echo \"Unit testing...\"\n" +
            "    sh \"`fail-the-build`\"\n" + //fail the build intentionally
            "  }\n" +
            "},'integration':{\n" +
            "  node{\n" +
            "    echo \"Integration testing...\"\n" +
            "  }\n" +
            "}, 'ui':{\n" +
            "  node{\n" +
            "    echo \"UI testing...\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "stage 'deploy'\n" +
            "node{\n" +
            "  echo \"Deploying\"\n" +
            "}"
        ));
        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, b1);

        NodeGraphBuilder builder = NodeGraphBuilder.NodeGraphBuilderFactory.getInstance(b1);
        List<FlowNode> nodes = getStagesAndParallels(builder);
        List<FlowNode> parallelNodes = getParallelNodes(builder);

        Assert.assertEquals(5, nodes.size());
        Assert.assertEquals(3, parallelNodes.size());

        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);

        Assert.assertEquals(nodes.size(), resp.size());
        for(int i=0; i< nodes.size();i++){
            FlowNode n = nodes.get(i);
            Map rn = resp.get(i);
            Assert.assertEquals(n.getId(), rn.get("id"));
            Assert.assertEquals(getNodeName(n), rn.get("displayName"));

            List<Map> edges = (List<Map>) rn.get("edges");


            if(n.getDisplayName().equals("test")){
                Assert.assertEquals(parallelNodes.size(), edges.size());
                Assert.assertEquals(edges.get(i).get("id"), parallelNodes.get(i).getId());
                Assert.assertEquals("FAILURE", rn.get("result"));
            }else if(n.getDisplayName().equals("build")){
                Assert.assertEquals(1, edges.size());
                Assert.assertEquals(edges.get(i).get("id"), nodes.get(i+1).getId());
                Assert.assertEquals("SUCCESS", rn.get("result"));
            }else if(n.getDisplayName().equals("Branch: unit")){
                Assert.assertEquals(0, edges.size());
                Assert.assertEquals("FAILURE", rn.get("result"));
            }else{
                Assert.assertEquals(0, edges.size());
                Assert.assertEquals("SUCCESS", rn.get("result"));
            }
        }
    }

    @Test
    public void getPipelineJobRunNodeNoStageTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");


        job1.setDefinition(new CpsFlowDefinition("node{\n" +
            "  parallel 'unit':{\n" +
            "    node{\n" +
            "      sh \"Unit testing...\"\n" +
            "    }\n" +
            "  },'integration':{\n" +
            "    node{\n" +
            "      echo \"Integration testing...\"\n" +
            "    }\n" +
            "  }, 'ui':{\n" +
            "    node{\n" +
            "      echo \"UI testing...\"\n" +
            "    }\n" +
            "  }\n" +
            "}"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        //j.assertBuildStatusSuccess(b1);
//        FlowGraphTable nodeGraphTable = new FlowGraphTable(b1.getExecution());
//        nodeGraphTable.build();
//        List<FlowNode> nodes = getStages(nodeGraphTable);
//        List<FlowNode> parallelNodes = getParallelNodes(nodeGraphTable);
//
//        Assert.assertEquals(3, nodes.size());
//        Assert.assertEquals(3, parallelNodes.size());
//
//        // get all nodes for pipeline1
        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+resp.get(0).get("id")+"/steps/", List.class);
//        Assert.assertEquals(nodes.size(), resp.size());

    }


    @Test
    public void getPipelineJobRunNodeTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");


        job1.setDefinition(new CpsFlowDefinition("stage 'build'\n" +
            "node{\n" +
            "  echo \"Building...\"\n" +
            "}\n" +
            "\n" +
            "stage 'test'\n" +
            "parallel 'unit':{\n" +
            "  node{\n" +
            "    echo \"Unit testing...\"\n" +
            "  }\n" +
            "},'integration':{\n" +
            "  node{\n" +
            "    echo \"Integration testing...\"\n" +
            "  }\n" +
            "}, 'ui':{\n" +
            "  node{\n" +
            "    echo \"UI testing...\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "stage 'deploy'\n" +
            "node{\n" +
            "  echo \"Deploying\"\n" +
            "}"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);
        FlowGraphTable nodeGraphTable = new FlowGraphTable(b1.getExecution());
        nodeGraphTable.build();
        List<FlowNode> nodes = getStages(nodeGraphTable);
        List<FlowNode> parallelNodes = getParallelNodes(nodeGraphTable);

        Assert.assertEquals(6, nodes.size());
        Assert.assertEquals(3, parallelNodes.size());

        // get all nodes for pipeline1
        List<Map> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(nodes.size(), resp.size());

        //Get a node detail
        FlowNode n = nodes.get(0);

        Map node = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+n.getId());

        List<Map> edges = (List<Map>) node.get("edges");

        Assert.assertEquals(n.getId(), node.get("id"));
        Assert.assertEquals(getNodeName(n), node.get("displayName"));
        Assert.assertEquals("SUCCESS", node.get("result"));
        Assert.assertEquals(1, edges.size());
        Assert.assertEquals(nodes.get(1).getId(), edges.get(0).get("id"));


        //Get a parllel node detail
        node = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/"+parallelNodes.get(0).getId());

        n = parallelNodes.get(0);
        edges = (List<Map>) node.get("edges");

        Assert.assertEquals(n.getId(), node.get("id"));
        Assert.assertEquals(getNodeName(n), node.get("displayName"));
        Assert.assertEquals("SUCCESS", node.get("result"));
        Assert.assertEquals(1, edges.size());
        Assert.assertEquals(nodes.get(nodes.size()-1).getId(), edges.get(0).get("id"));
    }


    @Test
    public void getPipelineJobRunNodeLogTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");


        job1.setDefinition(new CpsFlowDefinition("stage 'build'\n" +
            "node{\n" +
            "  echo \"Building...\"\n" +
            "}\n" +
            "\n" +
            "stage 'test'\n" +
            "parallel 'unit':{\n" +
            "  node{\n" +
            "    echo \"Unit testing...\"\n" +
            "  }\n" +
            "},'integration':{\n" +
            "  node{\n" +
            "    echo \"Integration testing...\"\n" +
            "  }\n" +
            "}, 'ui':{\n" +
            "  node{\n" +
            "    echo \"UI testing...\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "stage 'deploy'\n" +
            "node{\n" +
            "  echo \"Deploying\"\n" +
            "}"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);
        FlowGraphTable nodeGraphTable = new FlowGraphTable(b1.getExecution());
        nodeGraphTable.build();
        List<FlowNode> nodes = getStages(nodeGraphTable);
        List<FlowNode> parallelNodes = getParallelNodes(nodeGraphTable);

        Assert.assertEquals(6, nodes.size());
        Assert.assertEquals(3, parallelNodes.size());

        String output = get("/organizations/jenkins/pipelines/pipeline1/runs/1/log", String.class);
        assertNotNull(output);
        System.out.println(output);
    }

    @Test
    public void getPipelineJobRunStepLogTest() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");


        job1.setDefinition(new CpsFlowDefinition("stage 'build'\n" +
            "node{\n" +
            "  echo \"Building...\"\n" +
            "}\n" +
            "\n" +
            "stage 'test'\n" +
            "parallel 'unit':{\n" +
            "  node{\n" +
            "    echo \"Unit testing...\"\n" +
            "  }\n" +
            "},'integration':{\n" +
            "  node{\n" +
            "    echo \"Integration testing...\"\n" +
            "  }\n" +
            "}, 'ui':{\n" +
            "  node{\n" +
            "    echo \"UI testing...\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "stage 'deploy'\n" +
            "node{\n" +
            "  echo \"Deploying\"\n" +
            "}"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);

        PipelineNodeGraphBuilder graphBuilder = new PipelineNodeGraphBuilder(b1);
        List<FlowNode> flowNodes = graphBuilder.getAllSteps();

        Map resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/"+flowNodes.get(0).getId()+"/");

        String linkToLog = getActionLink(resp, "org.jenkinsci.plugins.workflow.actions.LogAction");

        assertNotNull(linkToLog);
        assertEquals("/blue/rest/organizations/jenkins/pipelines/pipeline1/runs/1/steps/6/log/", linkToLog);
        String output = get(linkToLog.substring("/blue/rest".length()), String.class);
        Assert.assertNotNull(output);
    }

    @Test
    public void BlockStageNodesFailureTest1() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition("node{\n" +
            "    stage ('Build') {\n" +
            "            sh 'echo1 \"Building\"'\n" +
            "    }\n" +
            "    stage ('Test') {\n" +
            "            sh 'echo testing'\n" +
            "    }\n" +
            "    stage ('Deploy') {\n" +
            "            sh 'echo deploy'\n" +
            "    }\n" +
            "}"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, b1);
        List<Map> nodes = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(1, nodes.size());
        Assert.assertEquals("FAILURE", nodes.get(0).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(0).get("state"));

    }


    @Test
    public void BlockStageNodesFailureTest2() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition("node{\n" +
            "    stage ('Build') {\n" +
            "            sh 'echo \"Building\"'\n" +
            "    }\n" +
            "    stage ('Test') {\n" +
            "            sh 'echo1 testing'\n" +
            "    }\n" +
            "    stage ('Deploy') {\n" +
            "            sh 'echo deploy'\n" +
            "    }\n" +
            "}"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, b1);
        List<Map> nodes = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(2, nodes.size());
        Assert.assertEquals("SUCCESS", nodes.get(0).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(0).get("state"));
        Assert.assertEquals("FAILURE", nodes.get(1).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(1).get("state"));

    }

    @Test
    public void BlockStageNodesFailureTest3() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition("node{\n" +
            "    stage ('Build') {\n" +
            "            sh 'echo \"Building\"'\n" +
            "    }\n" +
            "    stage ('Test') {\n" +
            "            sh 'echo testing'\n" +
            "    }\n" +
            "    stage ('Deploy') {\n" +
            "            sh 'echo1 deploy'\n" +
            "    }\n" +
            "}"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, b1);
        get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        List<Map> nodes = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(3, nodes.size());
        Assert.assertEquals("SUCCESS", nodes.get(0).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(0).get("state"));
        Assert.assertEquals("SUCCESS", nodes.get(1).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(1).get("state"));
        Assert.assertEquals("FAILURE", nodes.get(2).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(2).get("state"));
    }

    @Test
    public void KyotoNodesFailureTest1() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition("pipeline {\n" +
            "    agent label:''\n" +
            "    stages {\n" +
            "        stage ('Build') {\n" +
                "steps{\n" +
            "            sh 'echo1 \"Building\"'\n" +
            "        }\n" +
                "}\n" +
            "        stage ('Test') {\n" +
                "steps{\n" +
            "            sh 'echo \"Building\"'\n" +
            "        }\n" +
                "}\n" +
            "        stage ('Deploy') {\n" +
                "steps{\n" +
            "            sh 'echo \"Building\"'\n" +
            "        }\n" +
                "}\n" +
            "    }\n" +
            "}\n"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, b1);
        List<Map> nodes = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(3, nodes.size());

        Assert.assertEquals("FAILURE", nodes.get(0).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(0).get("state"));

        Assert.assertEquals("NOT_BUILT",nodes.get(1).get("result"));
        Assert.assertEquals("NOT_BUILT",nodes.get(1).get("state"));

        Assert.assertEquals("NOT_BUILT",nodes.get(2).get("result"));
        Assert.assertEquals("NOT_BUILT",nodes.get(2).get("state"));
    }

    @Test
    public void KyotoNodesFailureTest2() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition("pipeline {\n" +
            "    agent label:''\n" +
            "    stages {\n" +
            "        stage ('Build') {\n" +
                "steps{\n" +
            "            sh 'echo \"Building\"'\n" +
                "}\n"+
            "        }\n" +
            "        stage ('Test') {\n" +
                "steps{\n" +
            "            sh 'echo \"Building\"'\n" +
            "            sh 'echo2 \"Building finished\"'\n" +
                "}\n" +
            "        }\n" +
            "        stage ('Deploy') {\n" +
                "steps{\n" +
            "            sh 'echo \"Building\"'\n" +
                "}\n"+
            "        }\n" +
            "    }\n" +
            "}\n"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, b1);
        List<Map> nodes = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(3, nodes.size());
        Assert.assertEquals("SUCCESS", nodes.get(0).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(0).get("state"));
        Assert.assertEquals("FAILURE", nodes.get(1).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(1).get("state"));
    }

    @Test
    public void KyotoNodesFailureTest3() throws Exception {
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition("pipeline {\n" +
            "    agent label:''\n" +
            "    stages {\n" +
            "        stage ('Build') {\n" +
                "steps{\n"+
            "            sh 'echo \"Building\"'\n" +
                "}\n"+
            "        }\n" +
            "        stage ('Test') {\n" +
                "steps{\n"+
            "            sh 'echo \"Testing\"'\n" +
                "}\n"+
            "        }\n" +
            "        stage ('Deploy') {\n" +
                "steps{\n"+
            "            sh 'echo1 \"Deploying\"'\n" +
                "}\n"+
            "        }\n" +
            "    }\n" +
            "}\n"));

        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, b1);
        List<Map> nodes = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(3, nodes.size());
        Assert.assertEquals("SUCCESS", nodes.get(0).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(0).get("state"));
        Assert.assertEquals("SUCCESS", nodes.get(1).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(1).get("state"));
        Assert.assertEquals("FAILURE", nodes.get(2).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(2).get("state"));
    }


    @Test
    public void waitForInputTest() throws Exception {
        String script = "node {\n" +
                "    stage(\"parallelStage\"){\n" +
                "      parallel left : {\n" +
                "            echo \"running\"\n" +
                "            def branchInput = input message: 'Please input branch to test against', parameters: [[$class: 'StringParameterDefinition', defaultValue: 'master', description: '', name: 'branch']]\n" +
                "            echo \"BRANCH NAME: ${branchInput}\"\n" +
                "        }, \n" +
                "        right : {\n" +
                "            sh 'sleep 100000'\n" +
                "        }\n" +
                "    }\n" +
                "}";

        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition(script));
        QueueTaskFuture<WorkflowRun> buildTask = job1.scheduleBuild2(0);
        WorkflowRun run = buildTask.getStartCondition().get();
        CpsFlowExecution e = (CpsFlowExecution) run.getExecutionPromise().get();

        while (run.getAction(InputAction.class) == null) {
            e.waitForSuspension();
        }

        Map runResp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/");

        Assert.assertEquals("PAUSED", runResp.get("state"));

        List<FlowNodeWrapper> nodes = NodeGraphBuilder.NodeGraphBuilderFactory.getInstance(run).getPipelineNodes();

        List<Map> nodesResp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);

        Assert.assertEquals("PAUSED", nodesResp.get(0).get("state"));
        Assert.assertEquals("UNKNOWN", nodesResp.get(0).get("result"));
        Assert.assertEquals("parallelStage", nodesResp.get(0).get("displayName"));

        Assert.assertEquals("PAUSED", nodesResp.get(1).get("state"));
        Assert.assertEquals("UNKNOWN", nodesResp.get(1).get("result"));
        Assert.assertEquals("left", nodesResp.get(1).get("displayName"));

        Assert.assertEquals("RUNNING", nodesResp.get(2).get("state"));
        Assert.assertEquals("UNKNOWN", nodesResp.get(2).get("result"));
        Assert.assertEquals("right", nodesResp.get(2).get("displayName"));

        List<Map> stepsResp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/", List.class);

        Assert.assertEquals("RUNNING", stepsResp.get(0).get("state"));
        Assert.assertEquals("UNKNOWN", stepsResp.get(0).get("result"));
        Assert.assertEquals("13", stepsResp.get(0).get("id"));

        Assert.assertEquals("PAUSED", stepsResp.get(2).get("state"));
        Assert.assertEquals("UNKNOWN", stepsResp.get(2).get("result"));
        Assert.assertEquals("12", stepsResp.get(2).get("id"));
    }

    @Test
    public void submitInput() throws Exception {
        String script = "node {\n" +
                "    stage(\"parallelStage\"){\n" +
                "      parallel left : {\n" +
                "            echo \"running\"\n" +
                "            def branchInput = input message: 'Please input branch to test against', parameters: [[$class: 'StringParameterDefinition', defaultValue: 'master', description: '', name: 'branch']]\n" +
                "            echo \"BRANCH NAME: ${branchInput}\"\n" +
                "        }, \n" +
                "        right : {\n" +
                "            sh 'echo 'right done''\n" +
                "        }\n" +
                "    }\n" +
                "}";

        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition(script));
        QueueTaskFuture<WorkflowRun> buildTask = job1.scheduleBuild2(0);
        WorkflowRun run = buildTask.getStartCondition().get();
        CpsFlowExecution e = (CpsFlowExecution) run.getExecutionPromise().get();

        while (run.getAction(InputAction.class) == null) {
            e.waitForSuspension();
        }


        List<Map> stepsResp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/", List.class);

        Assert.assertEquals("RUNNING", stepsResp.get(0).get("state"));
        Assert.assertEquals("UNKNOWN", stepsResp.get(0).get("result"));
        Assert.assertEquals("13", stepsResp.get(0).get("id"));

        Assert.assertEquals("PAUSED", stepsResp.get(2).get("state"));
        Assert.assertEquals("UNKNOWN", stepsResp.get(2).get("result"));
        Assert.assertEquals("12", stepsResp.get(2).get("id"));

        Map<String,Object> input = (Map<String, Object>) stepsResp.get(2).get("input");
        Assert.assertNotNull(input);
        String id = (String) input.get("id");
        Assert.assertNotNull(id);

        JSONObject param = new JSONObject();
        List<Map<String,Object>> params = (List<Map<String, Object>>) input.get("parameters");
        param.put("name", params.get(0).get("name"));
        param.put("value", "master");

        JSONObject req = new JSONObject();
        req.put("id", id);
        req.put("parameter", param);

        post("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/12/",req, 200);

        Thread.sleep(1000);
        Map<String,Object> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/12/");
        Assert.assertEquals("FINISHED", resp.get("state"));
        Assert.assertEquals("SUCCESS", resp.get("result"));
        Assert.assertEquals("12", resp.get("id"));
    }

    @Test
    public void abortInput() throws Exception {
        String script = "node {\n" +
                "    stage(\"parallelStage\"){\n" +
                "      parallel left : {\n" +
                "            echo \"running\"\n" +
                "            def branchInput = input message: 'Please input branch to test against', parameters: [[$class: 'StringParameterDefinition', defaultValue: 'master', description: '', name: 'branch']]\n" +
                "            echo \"BRANCH NAME: ${branchInput}\"\n" +
                "        }, \n" +
                "        right : {\n" +
                "            sh 'echo 'right done''\n" +
                "        }\n" +
                "    }\n" +
                "}";

        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition(script));
        QueueTaskFuture<WorkflowRun> buildTask = job1.scheduleBuild2(0);
        WorkflowRun run = buildTask.getStartCondition().get();
        CpsFlowExecution e = (CpsFlowExecution) run.getExecutionPromise().get();

        while (run.getAction(InputAction.class) == null) {
            e.waitForSuspension();
        }

        List<Map> stepsResp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/", List.class);

        Assert.assertEquals("RUNNING", stepsResp.get(0).get("state"));
        Assert.assertEquals("UNKNOWN", stepsResp.get(0).get("result"));
        Assert.assertEquals("13", stepsResp.get(0).get("id"));

        Assert.assertEquals("PAUSED", stepsResp.get(2).get("state"));
        Assert.assertEquals("UNKNOWN", stepsResp.get(2).get("result"));
        Assert.assertEquals("12", stepsResp.get(2).get("id"));

        Map<String,Object> input = (Map<String, Object>) stepsResp.get(2).get("input");
        Assert.assertNotNull(input);
        String id = (String) input.get("id");
        Assert.assertNotNull(id);

        JSONObject req = new JSONObject();
        req.put("id", id);
        req.put("abort", true);

        post("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/12/",req, 200);

        Thread.sleep(1000);
        Map<String,Object> resp = get("/organizations/jenkins/pipelines/pipeline1/runs/1/steps/12/");
        Assert.assertEquals("FINISHED", resp.get("state"));
        Assert.assertEquals("FAILURE", resp.get("result"));
        Assert.assertEquals("12", resp.get("id"));
    }


    @Test
    public void stageTestJENKINS_40135() throws Exception {
        String script = "node {\n" +
                "    stage 'Stage 1'\n" +
                "    stage 'Stage 2'\n" +
                "       echo 'hello'\n"+
                "}";
        WorkflowJob job1 = j.jenkins.createProject(WorkflowJob.class, "pipeline1");
        job1.setDefinition(new CpsFlowDefinition(script));
        WorkflowRun b1 = job1.scheduleBuild2(0).get();
        j.assertBuildStatusSuccess(b1);
        List<Map> nodes = get("/organizations/jenkins/pipelines/pipeline1/runs/1/nodes/", List.class);
        Assert.assertEquals(2, nodes.size());
        Assert.assertEquals("SUCCESS", nodes.get(0).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(0).get("state"));
        Assert.assertEquals("SUCCESS", nodes.get(1).get("result"));
        Assert.assertEquals("FINISHED", nodes.get(1).get("state"));
    }

    private String getActionLink(Map resp, String capability){
        List<Map> actions = (List<Map>) resp.get("actions");
        assertNotNull(actions);
        for(Map a: actions){
            String _class = (String) a.get("_class");
            Map r = get("/classes/"+_class+"/");
            List<String> classes = (List<String>) r.get("classes");
            for(String c:classes){
                if(c.equals(capability)){
                    return getHrefFromLinks(a,"self");
                }
            }
        }
        return null;
    }

}
