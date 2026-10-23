package io.mateu.ecdemo1.iaagent.workflow.application;

import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution;
import io.mateu.ecdemo1.iaagent.workflow.domain.WorkflowExecution.*;
import static io.mateu.ecdemo1.iaagent.workflow.application.WorkflowPorts.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Each checkpoint commits before the next remote action. Replays never resend an uncertain tool. */
public final class WorkflowAgentRunner {
    private final Store store; private final Catalogue catalogue; private final Model model;
    private final ToolFactory toolFactory; private final Codec codec; private final Clock clock;
    private final int maxRounds, maxCalls, maxToolErrors;
    private final Delegation delegation;
    public WorkflowAgentRunner(Store store, Catalogue catalogue, Model model, ToolFactory toolFactory,
                               Codec codec, Clock clock, int maxRounds, int maxCalls, Delegation delegation) {
        this(store,catalogue,model,toolFactory,codec,clock,maxRounds,maxCalls,5,delegation);
    }
    public WorkflowAgentRunner(Store store, Catalogue catalogue, Model model, ToolFactory toolFactory,
                               Codec codec, Clock clock, int maxRounds, int maxCalls, int maxToolErrors, Delegation delegation) {
        this.store=store; this.catalogue=catalogue; this.model=model; this.toolFactory=toolFactory;
        this.codec=codec; this.clock=clock; this.maxRounds=maxRounds; this.maxCalls=maxCalls;this.maxToolErrors=maxToolErrors;this.delegation=delegation;
    }
    public WorkflowExecution submit(Request request) {
        if (request.invocationId()==null || request.invocationId().isBlank()) throw new IllegalArgumentException("invocationId required");
        if (request.deadlineEpochMillis()<=0) throw new IllegalArgumentException("deadline required");
        return store.create(new WorkflowExecution(request, codec.digest(request)));
    }
    public void runRoot(String rootId) {
        try (var lock=store.lock(rootId)) {
            if (lock==null) return;
            var execution=required(rootId);
            if(execution.terminal()) return;
            if(store.cancellationRequested(rootId)){settleCancellation(rootId);return;}
            execute(execution);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void execute(WorkflowExecution e) {
        if(e.terminal() || e.status==Status.REVIEW_REQUIRED) return;
        try {
            Snapshot snapshot;
            if(e.snapshotId==null) {
                snapshot=e.request.parentInvocationId()==null ? catalogue.resolve(e.request,e.digest) : catalogue.snapshot(e.request.rootInvocationId());
                e.snapshotId=snapshot.id(); e.snapshotDigest=snapshot.digest(); e.catalogueRevision=snapshot.revision();
                store.save(e);
            } else {
                snapshot=catalogue.snapshot(e.request.rootInvocationId());
                if(!Objects.equals(snapshot.digest(),e.snapshotDigest)) throw new IllegalStateException("Snapshot changed");
            }
            catalogue.authorize(e.request);
            var agent=snapshot.agents().get(e.request.agentRef());
            if(agent==null || agent.outputSchema()==null || agent.outputSchema().isEmpty()) throw new IllegalArgumentException("Agent output schema required");
            if(e.request.chain().contains(agent.id()) || e.request.chain().size()>2) throw new IllegalArgumentException("A2A cycle or depth limit");
            e.status=Status.RUNNING; store.save(e); reply(e);
            if(e.reviewResolved){e.reviewResolved=false;store.save(e);}
            for(var pending:e.tools) if(pending.status==ToolStatus.REVIEW_REQUIRED || (pending.status==ToolStatus.DISPATCHED&&!pending.readOnly&&!"a2a".equals(pending.kind))) {
                pending.status=ToolStatus.REVIEW_REQUIRED;throw new ReviewNeeded("Unconfirmed tool "+pending.id);
            }
            try(var tools=toolFactory.open(effectiveRequest(e))) {
                if(e.messages.isEmpty()) {
                    e.messages.add(new Message("system",Objects.toString(agent.systemPrompt(),"")+"\n"+tools.systemContext()+"\nReturn exactly one JSON object matching this output schema:\n"+codec.encode(agent.outputSchema()),Map.of(),List.of(),null,null));
                    e.messages.add(new Message("user",(e.request.taskPrompt()==null?"":e.request.taskPrompt())+"\nInput JSON:\n"+codec.encode(e.request.input()),Map.of(),List.of(),null,null));
                    store.save(e);
                }
                while(!e.terminal() && e.status!=Status.REVIEW_REQUIRED) {
                    ensureDeadline(e);
                    if(!completeTools(e,tools)) return;
                    if(e.rounds.size()>e.completedRounds) {
                        var round=e.rounds.getLast();
                        if(round.assistant().calls().isEmpty()) {
                            codec.validate(agent.outputSchema(), round.assistant().text());
                            e.result=round.assistant().text(); e.status=Status.COMPLETED; store.save(e); reply(e); return;
                        }
                        e.completedRounds=e.rounds.size(); store.save(e);
                    }
                    if(e.rounds.size()>=maxRounds) throw new IllegalStateException("Model round limit exceeded");
                    catalogue.authorize(e.request);
                    var round=model.call(e.request.rootInvocationId(),agent.id(),List.copyOf(e.messages),tools.definitions(),effectiveRequest(e).deadlineEpochMillis());
                    if(round==null || round.assistant()==null) throw new IllegalStateException("Empty model response");
                    e.rounds.add(round); e.messages.add(round.assistant());
                    for(var call:round.assistant().calls()) {
                        if(e.tools.stream().anyMatch(t->t.id.equals(call.id()))) throw new IllegalArgumentException("Duplicate tool call id");
                        var definition=tools.definitions().stream().filter(t->t.name().equals(call.name())).findFirst();
                        var tool=new Tool(); tool.id=call.id(); tool.name=call.name(); tool.arguments=call.arguments();
                        Object arguments;try{arguments=codec.object(call.arguments());}catch(IllegalArgumentException invalid){arguments=Objects.toString(call.arguments(),"");}
                        tool.digest=codec.digest(Map.of("tool",call.name(),"arguments",arguments));
                        // An unknown tool is never dispatched; completeTools reports it back to the model.
                        tool.kind=definition.map(ToolSpec::kind).orElse(UNREGISTERED); tool.sourceId=definition.map(ToolSpec::sourceId).orElse(null); tool.readOnly=definition.map(ToolSpec::readOnly).orElse(true);
                        if("a2a".equals(tool.kind)) tool.childId=childId(e.request.invocationId(),e.rounds.size(),tool.id);
                        e.tools.add(tool);
                    }
                    store.save(e); // Complete provider response + requested calls precede dispatch.
                    store.outbox(e.request.invocationId()+":usage:"+e.rounds.size(),"usage",codec.encode(Map.of(
                            "rootInvocationId",e.request.rootInvocationId(),"invocationId",e.request.invocationId(),"agentId",agent.id(),
                            "round",e.rounds.size(),"inputTokens",round.inputTokens(),"outputTokens",round.outputTokens())));
                }
            }
        } catch (DelegationUnavailable pending) {e.status=Status.RUNNING;e.error=pending.getMessage();store.save(e);}
        catch (ReviewNeeded review) { review(e,review.getMessage()); }
        catch (Exception failure) {
            if(failure instanceof Cancelled && e.request.parentInvocationId()==null){settleCancellation(e.request.rootInvocationId());return;}
            // A durable child is recoverable by id. Only an uncertain effect (possibly in a child)
            // needs human review; a catalogue/network outage must not create an unreviewable hold.
            boolean unknown=e.tools.stream().anyMatch(t->t.status==ToolStatus.REVIEW_REQUIRED ||
                    (t.status==ToolStatus.DISPATCHED&&!t.readOnly&&!"a2a".equals(t.kind)) ||
                    ("a2a".equals(t.kind)&&t.childId!=null&&store.find(t.childId)!=null&&store.find(t.childId).status==Status.REVIEW_REQUIRED));
            boolean pendingChild=e.tools.stream().anyMatch(t->"a2a".equals(t.kind)&&t.status==ToolStatus.DISPATCHED);
            if(unknown)review(e,"Unconfirmed tool: "+failure.getMessage());
            else if(pendingChild){e.status=Status.RUNNING;e.error="Durable delegation pending: "+failure.getMessage();store.save(e);}
            else { e.status=failure instanceof Cancelled?Status.CANCELLED:Status.FAILED; e.error=failure.getMessage(); store.save(e); reply(e); }
        }
    }
    private boolean completeTools(WorkflowExecution e, Tools opened) {
        for(var t:e.tools) {
            if(t.status==ToolStatus.CONFIRMED) continue;
            if(t.status==ToolStatus.REVIEW_REQUIRED || (t.status==ToolStatus.DISPATCHED && !t.readOnly && !"a2a".equals(t.kind))) {
                t.status=ToolStatus.REVIEW_REQUIRED; throw new ReviewNeeded("Tool "+t.id+" may already have applied an effect");
            }
            ensureDeadline(e);
            if(store.children(e.request.rootInvocationId()).stream().flatMap(i->i.tools.stream()).filter(x->x.sent||(x.status!=ToolStatus.PREPARED&&!x.error)).count()>=maxCalls && t.status==ToolStatus.PREPARED)
                throw new IllegalStateException("Global tool-call limit exceeded");
            catalogue.authorize(e.request);
            var def=opened.definitions().stream().filter(d->d.name().equals(t.name)).findFirst();
            if(def.isEmpty()||UNREGISTERED.equals(t.kind)) { reject(e,t,new ToolOutcome.Rejected("Unregistered tool "+t.name,"unregistered_tool",false)); continue; }
            try { codec.validate(codec.object(def.get().inputSchema()),t.arguments); }
            catch(IllegalArgumentException invalid) { reject(e,t,new ToolOutcome.Rejected(bounded("Arguments violate the tool inputSchema: "+invalid.getMessage()),"invalid_arguments",false)); continue; }
            if("a2a".equals(t.kind)) {
                var child=child(e,t);
                delegation.dispatch(effectiveRequest(e),t);
                child=required(child.request.invocationId());
                if(child.status==Status.REVIEW_REQUIRED) throw new ReviewNeeded("Child "+child.request.invocationId()+" requires review");
                if(child.status!=Status.COMPLETED) {
                    t.status=ToolStatus.CONFIRMED;t.result=codec.encode(Map.of("childState",child.status.name(),"error",Objects.toString(child.error,"")));store.save(e);
                    if(child.status==Status.CANCELLED)throw new Cancelled();
                    throw new IllegalStateException("Child failed: "+child.error);
                }
                t.result=child.result;
            } else {
                t.status=ToolStatus.DISPATCHED; t.sent=true; store.save(e);
                ToolOutcome outcome;
                try { outcome=opened.call(def.get(),t.arguments,e.request.invocationId()+":"+t.id); }
                catch(RuntimeException failure) {
                    if(!t.readOnly) { t.status=ToolStatus.REVIEW_REQUIRED; store.save(e); throw new ReviewNeeded("Unconfirmed tool "+t.id); }
                    // Repeating a read-only call is safe: the model decides whether to retry.
                    outcome=new ToolOutcome.Rejected("Transient failure of a read-only tool; it may be retried","transient",true);
                }
                if(outcome instanceof ToolOutcome.Rejected rejected) { reject(e,t,rejected); continue; }
                t.result=((ToolOutcome.Ok)outcome).result();
            }
            t.status=ToolStatus.CONFIRMED;
            e.messages.add(new Message("tool",t.result,Map.of(),List.of(),t.id,t.name)); store.save(e);
        }
        return true;
    }
    /** A definitive refusal is confirmed and shown to the model; a retry is a new call with a new idempotency key. */
    private void reject(WorkflowExecution e, Tool t, ToolOutcome.Rejected rejected) {
        var body=new LinkedHashMap<String,Object>();body.put("error",true);body.put("code",rejected.code());body.put("transient",rejected.transientFailure());body.put("message",bounded(rejected.message()));
        t.status=ToolStatus.CONFIRMED; t.error=true; t.result=codec.encode(body);
        e.messages.add(new Message("tool",t.result,Map.of(),List.of(),t.id,t.name)); store.save(e);
        if(e.tools.stream().filter(x->x.error).count()>maxToolErrors) throw new IllegalStateException("Tool error limit exceeded: "+body.get("message"));
    }
    private static String bounded(String message) { var text=Objects.toString(message,""); return text.length()>500?text.substring(0,500)+"...":text; }
    private WorkflowExecution child(WorkflowExecution parent, Tool tool) {
        var args=codec.object(tool.arguments); var request=parent.request;
        var chain=new ArrayList<>(request.chain()); chain.add(request.agentRef());
        var childRequest=new Request(tool.childId,request.rootInvocationId(),request.invocationId(),null,null,null,
                tool.sourceId,String.valueOf(args.getOrDefault("message","")),args,null,null,null,"result",
                request.deadlineEpochMillis(),request.initiatingActor(),chain,request.timeoutMillis());
        tool.status=ToolStatus.DISPATCHED; store.save(parent);
        return submit(childRequest);
    }
    /** Accept only arguments already persisted by the trusted parent model round. */
    /** Child lock differs from the root coordinator lock, allowing remote A2A dispatch without deadlock. */
    public void runDelegation(String childId) {
        var child=required(childId);
        if(child.request.parentInvocationId()==null)throw new IllegalArgumentException("Expected delegated task");
        try(var lock=store.lock("child:"+childId)){if(lock!=null)execute(required(childId));}
        catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public WorkflowExecution acceptDelegation(String agentId,String parentId,String toolId,Map<String,Object> payload) {
        var parent=required(parentId);
        var tool=parent.tools.stream().filter(t->t.id.equals(toolId)&&"a2a".equals(t.kind)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown parent delegation"));
        if(!agentId.equals(tool.sourceId)||!codec.digest(codec.object(tool.arguments)).equals(codec.digest(payload)))
            throw new IllegalArgumentException("Delegation payload conflicts with parent ledger");
        catalogue.authorize(parent.request);
        var existing=store.find(tool.childId);
        if(existing==null)throw new IllegalArgumentException("Delegation has not been durably dispatched by its parent");
        return existing;
    }
    public List<WorkflowExecution> list() {return store.roots().stream().flatMap(root->store.children(root.request.rootInvocationId()).stream()).toList();}
    public WorkflowExecution get(String id) { return required(id); }
    public List<WorkflowExecution> chain(String rootId) { return store.children(rootId); }
    public void cancel(String rootId) {
        store.requestCancellation(rootId);
        try(var lock=store.lock(rootId)) {
            if(lock==null) return; // Persisted flag is observed before the next dispatch.
            settleCancellation(rootId);
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public void reconcile(String id,String toolId,String digest,String result,String evidence,String reviewer) {
        if(reviewer==null||reviewer.isBlank())throw new IllegalArgumentException("An identified reviewer is required");
        var before=required(id);
        try(var lock=store.lock(before.request.rootInvocationId())) {
            if(lock==null) throw new IllegalStateException("Invocation executing");
            var e=required(id);var t=e.tools.stream().filter(x->x.id.equals(toolId)).findFirst().orElseThrow();
            if(e.status!=Status.REVIEW_REQUIRED || t.status!=ToolStatus.REVIEW_REQUIRED || !t.digest.equals(digest) || evidence==null || evidence.isBlank())
                throw new IllegalArgumentException("Review requires matching tool digest and evidence");
            if(result==null)throw new IllegalArgumentException("Confirmed tool result required");
            t.result=result;t.evidence=evidence;t.reviewer=reviewer;t.status=ToolStatus.CONFIRMED;
            e.messages.add(new Message("tool",result,Map.of(),List.of(),t.id,t.name)); e.status=Status.QUEUED;e.error=null;e.effectiveDeadline=clock.millis()+Math.max(1,e.request.timeoutMillis());e.reviewResolved=true;store.save(e);
            for(var ancestor:store.children(e.request.rootInvocationId())) if(ancestor.status==Status.REVIEW_REQUIRED && !ancestor.request.invocationId().equals(id)) {
                ancestor.status=Status.QUEUED;ancestor.error=null;ancestor.effectiveDeadline=e.effectiveDeadline;ancestor.reviewResolved=true;store.save(ancestor);
            }
            if(store.cancellationRequested(e.request.rootInvocationId()))settleCancellation(e.request.rootInvocationId());
        }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalStateException(ex);}
    }
    /** Reconcile cancellation bottom-up. Child locks prevent overwriting a still-running remote worker. */
    private void settleCancellation(String rootId) {
        var chain=new ArrayList<>(store.children(rootId));chain.sort(Comparator.comparingInt((WorkflowExecution x)->x.request.chain().size()).reversed());
        for(var candidate:chain) {
            AutoCloseable childLock=null;
            try {
                if(candidate.request.parentInvocationId()!=null){childLock=store.lock("child:"+candidate.request.invocationId());if(childLock==null)continue;}
                var e=required(candidate.request.invocationId());if(e.terminal())continue;
                boolean changed=false;
                for(var t:e.tools) {
                    if("a2a".equals(t.kind)&&t.status!=ToolStatus.CONFIRMED&&t.childId!=null) {
                        var child=store.find(t.childId);
                        if(child!=null&&child.terminal()){t.status=ToolStatus.CONFIRMED;t.result=Objects.toString(child.result,"Child "+child.status);changed=true;}
                    }
                    if(t.status==ToolStatus.DISPATCHED&&!"a2a".equals(t.kind)){t.status=t.readOnly?ToolStatus.CONFIRMED:ToolStatus.REVIEW_REQUIRED;if(t.readOnly)t.result="Read-only operation cancelled";changed=true;}
                }
                boolean unknown=e.tools.stream().anyMatch(t->t.status==ToolStatus.DISPATCHED||t.status==ToolStatus.REVIEW_REQUIRED);
                var target=unknown?Status.REVIEW_REQUIRED:Status.CANCELLED;
                if(changed||e.status!=target){e.status=target;e.error=unknown?"Cancellation cannot confirm dispatched effects":"Workflow cancellation confirmed; recorded effects retained";store.save(e);reply(e);}
            }catch(RuntimeException ex){throw ex;}catch(Exception ex){throw new IllegalStateException(ex);}
            finally{if(childLock!=null)try{childLock.close();}catch(Exception ex){throw new IllegalStateException(ex);}}
        }
    }
    private Request effectiveRequest(WorkflowExecution e) {
        var r=e.request;var root=store.find(r.rootInvocationId());long deadline=root==null?e.effectiveDeadline:root.effectiveDeadline;
        return new Request(r.invocationId(),r.rootInvocationId(),r.parentInvocationId(),r.taskExecutionId(),r.processId(),r.stepId(),r.agentRef(),r.taskPrompt(),r.input(),r.mcp(),r.rag(),r.peers(),r.outputVariable(),deadline,r.initiatingActor(),r.chain(),r.timeoutMillis());
    }
    private void ensureDeadline(WorkflowExecution e) { if(store.cancellationRequested(e.request.rootInvocationId()))throw new Cancelled(); if(clock.millis()>=effectiveRequest(e).deadlineEpochMillis()) {store.requestCancellation(e.request.rootInvocationId());throw new Cancelled();} }
    private void review(WorkflowExecution e,String reason) { e.status=Status.REVIEW_REQUIRED;e.error=reason;store.save(e);reply(e); }
    private WorkflowExecution required(String id) {var e=store.find(id);if(e==null)throw new IllegalArgumentException("Unknown invocation");return e;}
    private void reply(WorkflowExecution e) {
        if(e.request.parentInvocationId()!=null)return;
        var variables=e.status==Status.COMPLETED?Map.of(e.request.outputVariable(),e.result):Map.of();
        var payload=new LinkedHashMap<String,Object>();payload.put("taskExecutionId",e.request.taskExecutionId());payload.put("processId",e.request.processId());
        payload.put("stepId",e.request.stepId());payload.put("invocationId",e.request.invocationId());payload.put("status",e.status.name());payload.put("variables",variables);
        payload.put("error",e.error);payload.put("reviewResolved",e.reviewResolved);payload.put("stateSequence",e.stateSequence);store.outbox(e.request.invocationId()+":reply:"+e.stateSequence,"reply",codec.encode(payload));
    }
    private static final String UNREGISTERED="unregistered";
    public static String childId(String parent,int round,String callId) {return UUID.nameUUIDFromBytes((parent+":"+round+":"+callId).getBytes(StandardCharsets.UTF_8)).toString();}
    private static final class Cancelled extends RuntimeException { Cancelled(){super("Workflow cancelled or deadline exceeded; confirmed effects are retained");} }
    private static final class ReviewNeeded extends RuntimeException { ReviewNeeded(String message){super(message);} }
}
