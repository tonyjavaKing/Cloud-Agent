package com.cloudagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

@SpringBootApplication
public class CloudAgentApplication {
  public static void main(String[] args) { SpringApplication.run(CloudAgentApplication.class, args); }

  @RestController
  public static class ApiController {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Path root = Paths.get(System.getProperty("user.dir"), "data", "sessions");
    private final ObjectMapper mapper = new ObjectMapper();
    public ApiController() { try { Files.createDirectories(root); } catch (IOException ignored) {} }
    private String now() { return Instant.now().toString(); }
    private Session find(String id) { return sessions.get(id); }
    private void emit(String id, String event, Object value) {
      String json; try { json = mapper.writeValueAsString(value); } catch (Exception e) { return; }
      for (SseEmitter emitter : new ArrayList<>(subscribers.getOrDefault(id, List.of()))) {
        try { emitter.send(SseEmitter.event().name(event).data(json)); }
        catch (Exception e) { emitter.complete(); subscribers.getOrDefault(id, List.of()).remove(emitter); }
      }
    }
    private void touch(Session s) { s.updatedAt = now(); }
    private Path workspace(String id) { return root.resolve(id); }
    private List<Artifact> artifacts(String id) {
      List<Artifact> result = new ArrayList<>(); Path dir = workspace(id); if (!Files.exists(dir)) return result;
      try (Stream<Path> paths = Files.walk(dir)) {
        paths.filter(Files::isRegularFile).forEach(p -> { try { result.add(new Artifact(dir.relativize(p).toString().replace('\\','/'), Files.size(p), Files.getLastModifiedTime(p).toInstant().toString())); } catch (IOException ignored) {} });
      } catch (IOException ignored) {}
      result.sort(Comparator.comparing(a -> a.name)); return result;
    }
    @GetMapping("/api/health") public Map<String,Object> health() { return Map.of("ok", true, "agentMode", System.getenv().getOrDefault("AGENT_MODE", "cli"), "agentCli", System.getenv().getOrDefault("AGENT_CLI", "codex")); }
    @GetMapping("/api/sessions") public Collection<Session> list() { return sessions.values().stream().sorted(Comparator.comparing((Session s) -> s.updatedAt).reversed()).toList(); }
    @PostMapping("/api/sessions") public Session create(@RequestBody(required=false) Map<String,String> body) throws IOException { String title=body==null?"新会话":body.getOrDefault("title","新会话"); Session s=new Session(UUID.randomUUID().toString(), title); sessions.put(s.id,s); Files.createDirectories(workspace(s.id)); return s; }
    @GetMapping("/api/sessions/{id}") public Session get(@PathVariable String id) { Session s=find(id); if(s==null) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"会话不存在"); s.artifacts=artifacts(id); return s; }
    @PostMapping("/api/sessions/{id}/messages") public Map<String,Boolean> message(@PathVariable String id,@RequestBody Map<String,String> body) { Session s=find(id); String content=body==null?"":body.getOrDefault("content","").trim(); if(s==null) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"会话不存在"); if(content.isBlank()) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"消息不能为空"); if("running".equals(s.status)) throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"当前会话仍在执行"); Message m=new Message("user",content,"completed"); s.messages.add(m); touch(s); emit(id,"message",m); executor.submit(()->execute(s,content)); return Map.of("accepted",true); }
    @PostMapping("/api/sessions/{id}/upload") public Map<String,Object> upload(@PathVariable String id,@RequestParam("file") MultipartFile file) throws IOException { Session s=find(id); if(s==null||file.isEmpty()) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"会话或文件无效"); Files.createDirectories(workspace(id)); String safe=Paths.get(Objects.requireNonNullElse(file.getOriginalFilename(),"uploaded-file")).getFileName().toString(); file.transferTo(workspace(id).resolve(safe)); Message m=new Message("system","已上传材料："+safe,"completed"); s.messages.add(m); s.artifacts=artifacts(id); touch(s); emit(id,"message",m); emit(id,"artifacts",s.artifacts); return Map.of("ok",true,"artifacts",s.artifacts); }
    @GetMapping(value="/api/sessions/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter events(@PathVariable String id) { Session s=find(id); if(s==null) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"会话不存在"); SseEmitter e=new SseEmitter(0L); subscribers.computeIfAbsent(id,k->new CopyOnWriteArrayList<>()).add(e); e.onCompletion(()->subscribers.getOrDefault(id,List.of()).remove(e)); e.onTimeout(e::complete); try{e.send(SseEmitter.event().name("snapshot").data(mapper.writeValueAsString(s)));}catch(Exception ignored){} return e; }
    @GetMapping("/api/sessions/{id}/files/**") public void file(@PathVariable String id,HttpServletRequest req,HttpServletResponse res)throws IOException { String prefix="/api/sessions/"+id+"/files/"; String rel=req.getRequestURI().substring(prefix.length()); Path base=workspace(id).toAbsolutePath().normalize(), target=base.resolve(rel).normalize(); if(!target.startsWith(base)||!Files.exists(target)||Files.isDirectory(target)){res.setStatus(404);return;} res.setContentType("application/octet-stream"); res.setHeader("Content-Disposition","attachment; filename=\""+target.getFileName()+"\""); Files.copy(target,res.getOutputStream()); }
    private void execute(Session s,String userText){ s.status="running"; Message a=new Message("assistant","","running"); s.messages.add(a); emit(s.id,"message",a); emit(s.id,"status",Map.of("status","running")); try { String prompt="你是云端 Agent 工作台中的执行助手。请直接完成用户任务，并在最后给出结果摘要。当前工作目录是唯一应读写的目录。\n本次任务："+userText; if("demo".equalsIgnoreCase(System.getenv().getOrDefault("AGENT_MODE","cli"))) runDemo(s,a,prompt); else runCli(s,a,prompt); a.status="completed"; s.status="completed"; } catch(Exception e){a.status="failed";a.content+="\n\n执行失败："+e.getMessage()+"\n请检查 AGENT_CLI、认证信息和容器配置。";s.status="error";} s.artifacts=artifacts(s.id); touch(s); emit(s.id,"message",a); emit(s.id,"artifacts",s.artifacts); emit(s.id,"status",Map.of("status",s.status)); }
    private void append(Session s,Message m,String text){m.content+=text;emit(s.id,"delta",Map.of("messageId",m.id,"delta",text));}
    private void runDemo(Session s,Message a,String prompt)throws Exception{String[] steps={"我已接收任务，正在拆解目标和交付物。\n","正在检查会话工作目录与已上传材料。\n","正在生成结果并把成果写入当前会话工作区。\n","正在整理摘要和可下载文件。\n"};for(String step:steps){Thread.sleep(400);append(s,a,step);}Files.writeString(workspace(s.id).resolve("task-result.md"),"# 任务记录\n\n"+prompt+"\n\n执行状态：已完成\n模式：本地演示模式\n");append(s,a,"\n已生成成果文件：task-result.md");}
    private void runCli(Session s,Message a,String prompt)throws Exception{String cli=System.getenv().getOrDefault("AGENT_CLI","codex");List<String> cmd=new ArrayList<>();if(cli.equals("codex"))cmd.addAll(List.of(cli,"exec","--dangerously-bypass-approvals-and-sandbox","--no-alt-screen","-C",workspace(s.id).toString(),prompt));else if(cli.equals("claude"))cmd.addAll(List.of(cli,"-p",prompt,"--dangerously-skip-permissions"));else cmd.addAll(List.of(cli,prompt));Process p=new ProcessBuilder(cmd).directory(workspace(s.id).toFile()).redirectErrorStream(true).start();try(BufferedReader r=p.inputReader(StandardCharsets.UTF_8)){String line;while((line=r.readLine())!=null)append(s,a,line+"\n");}if(p.waitFor()!=0)throw new IOException("Agent CLI exited with code "+p.exitValue());}
  }
  public static class Session { public String id,title,status="idle",createdAt,updatedAt; public List<Message> messages=new CopyOnWriteArrayList<>(); public List<Artifact> artifacts=new CopyOnWriteArrayList<>(); Session(String id,String title){this.id=id;this.title=title==null||title.isBlank()?"新会话":title;createdAt=updatedAt=Instant.now().toString();} }
  public static class Message { public String id=UUID.randomUUID().toString(),role,content,status,createdAt=Instant.now().toString(); Message(String role,String content,String status){this.role=role;this.content=content;this.status=status;} }
  public record Artifact(String name,long size,String updatedAt) {}
}


