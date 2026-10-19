package io.mateu.ecdemo1.iaagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import io.mateu.ecdemo1.iaagent.config.ChatClientRegistry;
import io.mateu.ecdemo1.iaagent.workflow.application.*;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.*;
import io.mateu.ecdemo1.iaagent.workflow.infra.*;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, signed JWTs and JSON-RPC; all endpoints are synthetic loopback fixtures. */
class WorkflowHttpProtocolTest {
    HttpServer server;String url;RSAKey key;String machineToken;ObjectMapper mapper=new ObjectMapper();
    WorkflowIdentity identity;HttpWorkflowCatalogue catalogue;
    final WorkflowAgentRunnerTest fixture=new WorkflowAgentRunnerTest();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        url="http://127.0.0.1:"+server.getAddress().getPort();key=new RSAKeyGenerator(2048).keyID("test-key").generate();
        machineToken=token("workflow-agent-executor","workflow-agent-executor","workflow-agent-executor",url);
        server.createContext("/jwks",exchange->{var json=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,json.length);exchange.getResponseBody().write(json);exchange.close();});
        server.createContext("/token",exchange->{var json=mapper.writeValueAsBytes(Map.of("access_token",machineToken,"expires_in",120));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,json.length);exchange.getResponseBody().write(json);exchange.close();});
        server.createContext("/internal/workflow-agent-executions/root/snapshot",exchange->{var json=mapper.writeValueAsBytes(snapshot());exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,json.length);exchange.getResponseBody().write(json);exchange.close();});
        server.start();identity=new WorkflowIdentity(url,url+"/jwks","workflow-agent-executor","workflow-agent-executor","secret",url+"/token",mapper);catalogue=new HttpWorkflowCatalogue(url,identity,mapper);
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    String token(String audience,String azp,String role,String issuer)throws Exception {return token(audience,azp,role,issuer,"technical-subject");}
    String token(String audience,String azp,String role,String issuer,String subject)throws Exception {var claims=new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience).expirationTime(Date.from(Instant.now().plusSeconds(120))).claim("azp",azp).claim("realm_access",Map.of("roles",List.of(role))).build();var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(key));return jwt.serialize();}
    Map<String,Object> snapshot(){
        var llm=Map.of("id","test-llm","name","Fixture","provider","OPENAI_COMPATIBLE","model","fixture-model","baseUrl",url+"/provider","apiKey","fixture-only");
        var assessor=new LinkedHashMap<String,Object>();assessor.put("agentId","assessor");assessor.put("systemPrompt","System");assessor.put("llm",llm);assessor.put("outputSchema",fixture.schema);assessor.put("peers",List.of(Map.of("id","peer","name","Peer","description","Delegate","a2aUrl",url+"/a2a/peer")));
        return Map.of("snapshotId","root","digest","frozen","catalogueRevision","revision","rootAgentId","assessor","agents",Map.of("assessor",assessor,"peer",Map.of("agentId","peer","systemPrompt","Peer","outputSchema",fixture.schema)));
    }
    @Test void durableA2aUsesHttpDataPartsAndReplaysSameChild()throws Exception {
        var posts=new AtomicInteger();var modelCalls=new AtomicInteger();var delegate=new HttpWorkflowA2a(catalogue,identity,mapper);
        var runner=new WorkflowAgentRunner(fixture.store,fixture.catalogue,(root,agent,messages,tools,deadline)->"assessor".equals(agent)&&modelCalls.getAndIncrement()==0?fixture.tool("a2a"):fixture.answer(),fixture.action(new AtomicInteger(),false,"a2a"),fixture.codec,Clock.systemUTC(),10,50,delegate);
        var controller=new WorkflowA2aController(identity,runner,mapper);
        server.createContext("/a2a/peer",exchange->{try{posts.incrementAndGet();var result=controller.rpc("peer",exchange.getRequestHeaders().getFirst("Authorization"),new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));var bytes=result.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception ex){exchange.sendResponseHeaders(403,-1);}finally{exchange.close();}});
        var r=fixture.request();runner.submit(new Request(r.invocationId(),r.rootInvocationId(),null,r.taskExecutionId(),r.processId(),r.stepId(),r.agentRef(),r.taskPrompt(),r.input(),null,null,null,r.outputVariable(),System.currentTimeMillis()+30000,r.initiatingActor(),r.chain(),30000));runner.runRoot("root");
        assertEquals(Status.COMPLETED,runner.get("root").status);assertEquals(1,posts.get());var tool=runner.get("root").tools.getFirst();var before=modelCalls.get();delegate.dispatch(runner.get("root").request,tool);assertEquals(before,modelCalls.get());assertEquals(2,posts.get());
        var rpc=Map.of("jsonrpc","2.0","id","get","method","tasks/get","params",Map.of("id",tool.childId));
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url+"/a2a/peer")).header("Authorization","Bearer "+machineToken).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rpc))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals("accepted",mapper.readTree(response.body()).path("result").path("artifacts").get(0).path("parts").get(0).path("data").path("decision").asText());
    }
    @Test void validatesSignatureIssuerAudienceAuthorizedClientAndOperatorRole()throws Exception {
        assertEquals("technical-subject",identity.authenticate("Bearer "+machineToken,false));
        assertThrows(SecurityException.class,()->identity.authenticate("Bearer "+token("wrong","workflow-agent-executor","workflow-agent-executor",url),false));
        assertThrows(SecurityException.class,()->identity.authenticate("Bearer "+token("workflow-agent-executor","browser","workflow-agent-executor",url),false));
        assertThrows(SecurityException.class,()->identity.authenticate("Bearer "+token("workflow-agent-executor","workflow-agent-executor","viewer",url),false));
        assertThrows(SecurityException.class,()->identity.authenticate("Bearer "+token("workflow-agent-executor","workflow-agent-executor","workflow-agent-executor","wrong-issuer"),false));
        assertEquals("technical-subject",identity.authenticate("Bearer "+token("account","control-plane","ai-admin",url),true));
        assertThrows(SecurityException.class,()->identity.authenticate("Bearer "+machineToken+"tampered",false));
    }
    @Test void rejectsSignedMachineAndOperatorTokensWithoutAnIdentifiedSubject()throws Exception {
        for(String subject:Arrays.asList(null,"   ")) {
            var machine=token("workflow-agent-executor","workflow-agent-executor","workflow-agent-executor",url,subject);
            var operator=token("account","control-plane","ai-admin",url,subject);
            assertThrows(SecurityException.class,()->identity.authenticate("Bearer "+machine,false));
            assertThrows(SecurityException.class,()->identity.authenticate("Bearer "+operator,true));
        }
    }
    @Test void providerToolCallUnknownFieldsSurviveCheckpointAndNextProviderRequest()throws Exception {
        var requests=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        server.createContext("/provider/v1/chat/completions",exchange->{try{
            requests.add(mapper.readTree(exchange.getRequestBody()));String message;
            if(requests.size()==1)message="{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"action\",\"arguments\":\"{}\"},\"extra_content\":{\"google\":{\"thought_signature\":\"opaque-signature\"}}}]}";
            else message="{\"role\":\"assistant\",\"content\":\"{\\\"decision\\\":\\\"accepted\\\"}\"}";
            var json=("{\"id\":\"chat\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"message\":"+message+",\"finish_reason\":\""+(requests.size()==1?"tool_calls":"stop")+"\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,json.length);exchange.getResponseBody().write(json);
        }finally{exchange.close();}});
        var model=new SpringAiWorkflowModel(new ChatClientRegistry(ObservationRegistry.NOOP),catalogue,mapper);
        var history=new ArrayList<Message>();history.add(new Message("system","Return JSON",Map.of(),List.of(),null,null));history.add(new Message("user","Assess",Map.of(),List.of(),null,null));
        var tools=List.of(new ToolSpec("action","Action","{\"type\":\"object\"}","mcp","fixture",false));
        var first=model.call("root","assessor",history,tools,System.currentTimeMillis()+30000);assertEquals(1,first.assistant().calls().size());
        var replay=mapper.readValue(mapper.writeValueAsString(first),Round.class);history.add(replay.assistant());history.add(new Message("tool","{}",Map.of(),List.of(),"call1","action"));
        var answer=model.call("root","assessor",history,tools,System.currentTimeMillis()+30000);assertTrue(answer.assistant().text().contains("accepted"));
        var assistant=requests.get(1).path("messages").get(2);assertEquals("opaque-signature",assistant.path("tool_calls").get(0).path("extra_content").path("google").path("thought_signature").asText());assertEquals(2,requests.size());
    }
}
