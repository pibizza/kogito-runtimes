package org.jbpm.persistence.session;

import java.io.*;
import java.util.*;
import static org.drools.persistence.util.PersistenceUtil.JBPM_PERSISTENCE_UNIT_NAME;
import static org.drools.runtime.EnvironmentName.ENTITY_MANAGER_FACTORY;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import org.drools.KnowledgeBase;
import org.drools.KnowledgeBaseFactory;
import org.drools.SystemEventListenerFactory;
import org.drools.builder.*;
import org.drools.io.impl.ClassPathResource;
import org.drools.marshalling.ObjectMarshallingStrategy;
import org.drools.marshalling.impl.ClassObjectMarshallingStrategyAcceptor;
import org.drools.marshalling.impl.SerializablePlaceholderResolverStrategy;
import org.drools.marshalling.util.MarshallingTestUtil;
import org.drools.persistence.jpa.JPAKnowledgeService;
import org.drools.persistence.jpa.marshaller.JPAPlaceholderResolverStrategy;
import org.drools.persistence.util.PersistenceUtil;
import org.drools.runtime.Environment;
import org.drools.runtime.EnvironmentName;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.runtime.process.ProcessInstance;
import org.jbpm.persistence.JbpmTestCase;
import org.jbpm.persistence.session.objects.MedicalRecord;
import org.jbpm.persistence.session.objects.MockUserInfo;
import org.jbpm.persistence.session.objects.Patient;
import org.jbpm.persistence.session.objects.RecordRow;
import org.jbpm.process.workitem.wsht.SyncWSHumanTaskHandler;
import org.jbpm.task.AccessType;
import org.jbpm.task.Content;
import org.jbpm.task.Group;
import org.jbpm.task.User;
import org.jbpm.task.query.TaskSummary;
import org.jbpm.task.service.ContentData;
import org.jbpm.task.service.SendIcal;
import org.jbpm.task.service.TaskService;
import org.jbpm.task.service.TaskServiceSession;
import org.jbpm.task.service.local.LocalTaskService;
import org.junit.*;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;
import org.mvel2.compiler.ExpressionCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PatientVariablePersistenceStrategyTest extends JbpmTestCase {

    private static Logger logger = LoggerFactory.getLogger(PatientVariablePersistenceStrategyTest.class);
    private HashMap<String, Object> context;
    private EntityManagerFactory emf;
    private EntityManagerFactory emfDomain;
    private EntityManagerFactory emfTasks;
    protected Map<String, User> users;
    protected Map<String, Group> groups;
    protected TaskService taskService;
    protected LocalTaskService localTaskService;
    protected TaskServiceSession taskSession;
    protected MockUserInfo userInfo;
    protected Properties conf;

    @Before
    public void setUp() throws Exception {
        context = PersistenceUtil.setupWithPoolingDataSource(JBPM_PERSISTENCE_UNIT_NAME);
        emf = (EntityManagerFactory) context.get(ENTITY_MANAGER_FACTORY);

        conf = new Properties();
        conf.setProperty("mail.smtp.host", "localhost");
        conf.setProperty("mail.smtp.port", "1125");
        conf.setProperty("from", "from@domain.com");
        conf.setProperty("replyTo", "replyTo@domain.com");
        conf.setProperty("defaultLanguage", "en-UK");
        SendIcal.initInstance(conf);

        // Use persistence.xml configuration
        emfDomain = Persistence.createEntityManagerFactory("org.jbpm.persistence.patient.example");
        emfTasks = Persistence.createEntityManagerFactory("org.jbpm.task");
        Reader reader = null;
        Map vars = new HashMap();
        try {
            reader = new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream("LoadUsers.mvel"));
            users = (Map<String, User>) eval(reader, vars);
        } finally {
            if (reader != null) {
                reader.close();
            }
            reader = null;
        }

        try {
            reader = new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream("LoadGroups.mvel"));
            groups = (Map<String, Group>) eval(reader, vars);
        } finally {
            if (reader != null) {
                reader.close();
            }
        }

        userInfo = new MockUserInfo();

        taskService = new TaskService(emfTasks, SystemEventListenerFactory.getSystemEventListener(), null);
        taskSession = taskService.createSession();

        taskService.setUserinfo(userInfo);

        for (User user : users.values()) {
            taskSession.addUser(user);
        }

        for (Group group : groups.values()) {
            taskSession.addGroup(group);
        }

        localTaskService = new LocalTaskService(taskService);

    }

    @After
    public void tearDown() throws Exception {
        PersistenceUtil.tearDown(context);
        
        if(localTaskService != null){
            System.out.println("Disposing Local Task Service session");
            localTaskService.disconnect();
        }
        if(taskSession != null){
            System.out.println("Disposing session");
            taskSession.dispose();
        }
    }

    @AfterClass
    public static void compareMarshalledData() {
        MarshallingTestUtil.compareMarshallingDataFromTest(JBPM_PERSISTENCE_UNIT_NAME);
    }

    @Test
    public void simplePatientMedicalRecordTest() throws Exception {
        Patient salaboy = new Patient("salaboy");
        MedicalRecord medicalRecord = new MedicalRecord("Last Three Years Medical Hisotry", salaboy);

        EntityManager em = emfDomain.createEntityManager();
        
        em.getTransaction().begin();
        em.persist(medicalRecord);
        em.getTransaction().commit();
        Environment env = createEnvironment();
        KnowledgeBase kbase = createKnowledgeBase("patient-appointment.bpmn");
        StatefulKnowledgeSession ksession = createSession(kbase, env);
        SyncWSHumanTaskHandler htHandler = new SyncWSHumanTaskHandler(localTaskService, ksession);
        htHandler.setLocal(true);
        ksession.getWorkItemManager().registerWorkItemHandler("Human Task", htHandler);
        logger.info("### Starting process ###");
        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("medicalRecord", medicalRecord);
        ProcessInstance process = ksession.startProcess("org.jbpm.PatientAppointment", parameters); 
        long processInstanceId = process.getId();

        //The process is in the first Human Task waiting for its completion
        Assert.assertEquals(ProcessInstance.STATE_ACTIVE, process.getState());
        
        //gets frontDesk's tasks
        List<TaskSummary> frontDeskTasks = this.localTaskService.getTasksAssignedAsPotentialOwner("frontDesk", "en-UK");
        Assert.assertEquals(1, frontDeskTasks.size());
        
         //doctor doesn't have any task
        List<TaskSummary> doctorTasks = this.localTaskService.getTasksAssignedAsPotentialOwner("doctor", "en-UK");
        Assert.assertTrue(doctorTasks.isEmpty());
        
        //manager doesn't have any task
        List<TaskSummary> managerTasks = this.localTaskService.getTasksAssignedAsPotentialOwner("manager", "en-UK");
        Assert.assertTrue(managerTasks.isEmpty());
        
        
        this.localTaskService.start(frontDeskTasks.get(0).getId(), "frontDesk");
        //frontDesk completes its task
        MedicalRecord taskMedicalRecord = getTaskContent(frontDeskTasks.get(0));
        Assert.assertNotNull(taskMedicalRecord.getId());
        taskMedicalRecord.setDesc("Initial Description of the Medical Record");
        
        em.getTransaction().begin();
        em.merge(taskMedicalRecord);
        em.getTransaction().commit();
        
      
        this.localTaskService.complete(frontDeskTasks.get(0).getId(), "frontDesk", null);
        
        //Now doctor has 1 task
        doctorTasks = this.localTaskService.getTasksAssignedAsPotentialOwner("doctor", "en-UK");
        Assert.assertEquals(1, doctorTasks.size());
        
         //No tasks for manager yet
        managerTasks = this.localTaskService.getTasksAssignedAsPotentialOwner("manager", "en-UK");
        Assert.assertTrue(managerTasks.isEmpty());
        
        taskMedicalRecord = getTaskContent(doctorTasks.get(0));
        
        this.localTaskService.start(doctorTasks.get(0).getId(), "doctor");
        //Check that we have the Modified Document
        Assert.assertEquals("Initial Description of the Medical Record", taskMedicalRecord.getDesc());
        em.getTransaction().begin();
        taskMedicalRecord.setDesc("Medical Record Validated by Doctor");
        List<RecordRow> rows = new ArrayList<RecordRow>();
        rows.add(new RecordRow("CODE-999", "Just a regular Cold"));
        taskMedicalRecord.setRows(rows);
        taskMedicalRecord.setPriority(1);
        
        em.getTransaction().commit();
        
        this.localTaskService.complete(doctorTasks.get(0).getId(), "doctor", null);
        
         // tasks for manager 
        managerTasks = this.localTaskService.getTasksAssignedAsPotentialOwner("manager", "en-UK");
        Assert.assertEquals(1, managerTasks.size());
        this.localTaskService.start(managerTasks.get(0).getId(), "manager");
        
        em.getTransaction().begin();
        Patient patient = taskMedicalRecord.getPatient();
        patient.setNextAppointment(new Date());
        
        em.getTransaction().commit();
        
        this.localTaskService.complete(managerTasks.get(0).getId(), "manager", null);
        
        Assert.assertEquals(ProcessInstance.STATE_COMPLETED, process.getState());
        
        
        
    }

    private StatefulKnowledgeSession createSession(KnowledgeBase kbase, Environment env) {
        return JPAKnowledgeService.newStatefulKnowledgeSession(kbase, null, env);
    }

    private StatefulKnowledgeSession reloadSession(StatefulKnowledgeSession ksession, KnowledgeBase kbase, Environment env) {
        int sessionId = ksession.getId();
        ksession.dispose();
        return JPAKnowledgeService.loadStatefulKnowledgeSession(sessionId, kbase, null, env);
    }

    private KnowledgeBase createKnowledgeBase(String flowFile) {
        KnowledgeBuilderConfiguration conf = KnowledgeBuilderFactory.newKnowledgeBuilderConfiguration();
        conf.setProperty("drools.dialect.java.compiler", "JANINO");
        KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder(conf);
        kbuilder.add(new ClassPathResource(flowFile), ResourceType.BPMN2);
        if (kbuilder.hasErrors()) {
            StringBuilder errorMessage = new StringBuilder();
            for (KnowledgeBuilderError error : kbuilder.getErrors()) {
                errorMessage.append(error.getMessage());
                errorMessage.append(System.getProperty("line.separator"));
            }
            fail(errorMessage.toString());
        }

        KnowledgeBase kbase = KnowledgeBaseFactory.newKnowledgeBase();
        kbase.addKnowledgePackages(kbuilder.getKnowledgePackages());
        return kbase;
    }

    private Environment createEnvironment() {
        Environment env = PersistenceUtil.createEnvironment(context);
        env.set(EnvironmentName.OBJECT_MARSHALLING_STRATEGIES, new ObjectMarshallingStrategy[]{
                    new JPAPlaceholderResolverStrategy(env),
                    new SerializablePlaceholderResolverStrategy(ClassObjectMarshallingStrategyAcceptor.DEFAULT)
                });
        return env;
    }

    public Object eval(Reader reader,
            Map vars) {
        try {
            return eval(toString(reader),
                    vars);
        } catch (IOException e) {
            throw new RuntimeException("Exception Thrown",
                    e);
        }
    }

    public String toString(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder(1024);
        int charValue;

        while ((charValue = reader.read()) != -1) {
            sb.append((char) charValue);
        }
        return sb.toString();
    }

    public Object eval(String str, Map vars) {
        ExpressionCompiler compiler = new ExpressionCompiler(str.trim());

        ParserContext context = new ParserContext();
        context.addPackageImport("org.jbpm.task");
        context.addPackageImport("org.jbpm.task.service");
        context.addPackageImport("org.jbpm.task.query");
        context.addPackageImport("java.util");

        vars.put("now", new Date());
        return MVEL.executeExpression(compiler.compile(context), vars);
    }
    
    private MedicalRecord getTaskContent(TaskSummary summary) throws IOException, ClassNotFoundException{
        logger.info(" >>> Getting Task Content = "+summary.getId());
        Content content = this.localTaskService.getContent(summary.getId());
        
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(content.getContent()));
        Object readObject = ois.readObject();
        logger.info(" >>> Object = "+readObject);
        return (MedicalRecord)readObject;
    }
    
    /**
     * Convert a Map<String, Object> into a ContentData object.
     * @param data
     * @return 
     */
    private ContentData prepareContentData(Map data){
        ContentData contentData = null;
        if (data != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream out;
                try {
                        out = new ObjectOutputStream(bos);
                        out.writeObject(data);
                        out.close();
                        contentData = new ContentData();
                        contentData.setContent(bos.toByteArray());
                        contentData.setAccessType(AccessType.Inline);
                }
                catch (IOException e) {
                        System.err.print(e);
                }
        }
        
        return contentData;
    }
}
