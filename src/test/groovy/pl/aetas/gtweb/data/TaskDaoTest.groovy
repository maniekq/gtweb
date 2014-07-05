package pl.aetas.gtweb.data
import com.mongodb.BasicDBObject
import com.mongodb.DBCollection
import com.mongodb.DBObject
import org.bson.types.ObjectId
import org.joda.time.DateMidnight
import org.joda.time.DateTime
import pl.aetas.gtweb.domain.Tag
import pl.aetas.gtweb.domain.Task

class TaskDaoTest extends DaoTestBase {

    TaskDao taskDao
    TagDao tagDao
    DbTasksConverter dbTasksConverter

    void setup() {
        cleanup()
        dbTasksConverter = new DbTasksConverter()
        tagDao = new TagDao(tagsCollection, new DbTagConverter(), tasksCollection)
        taskDao = new TaskDao(tasksCollection, tagDao, dbTasksConverter)
    }

    void cleanup() {
        tasksCollection.drop()
        tagsCollection.drop()
    }

    def "should create new task in DB"() {
        given:
        tagsCollection.insert(new BasicDBObject([name:'tagA', owner_id:'mariusz', color:null, visible_in_workview:false]))
        tagsCollection.insert(new BasicDBObject([name:'tagB', owner_id:'mariusz', color:'black', visible_in_workview:true]))
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle')
                .setCreatedDate(new Date())
                .setDescription('desc')
                .addTag(new Tag('123', 'mariusz', 'tagA', null, false))
                .addTag(new Tag('546', 'mariusz', 'tagB', 'black', true))
                .setFinished(false)
                .setStartDate(DateMidnight.parse('2014-02-27').toDate())
                .setDueDate(DateMidnight.parse('2014-03-13').toDate())
                .build()
        when:
        task = taskDao.insert(task)
        then:
        DBObject taskFromDb = tasksCollection.findOne(new BasicDBObject('_id', new ObjectId(task.getId())))
        taskFromDb.owner_id == 'mariusz'
        taskFromDb.title == 'taskTitle'
        taskFromDb.description == 'desc'
        taskFromDb.tags.size() == 2
        taskFromDb.finished == false
        taskFromDb.closed_date == null
        taskFromDb.start_date == DateMidnight.parse('2014-02-27').toDate()
        taskFromDb.due_date == DateMidnight.parse('2014-03-13').toDate()
    }

    def "should throw exception when trying to insert task with parent task set"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate()).setId(ObjectId.get().toString())
                .build()
        def subtask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate()).setParentTask(parentTask)
                .build()
        when:
        taskDao.insert(subtask)
        then:
        thrown(UnsupportedDataOperationException)
    }

    def "should set task id in the returned task object after insert"() {
        given:
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle').setCreatedDate(new Date()).build()
        when:
        def taskAfterUpdate = taskDao.insert(task)
        then:
        taskAfterUpdate.getId() != null
    }

    def "should throw exception when creating task with subtasks"() {
        given:
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle').setCreatedDate(new Date())
                .addSubtask(new Task.TaskBuilder().setOwnerId('mariusz').setTitle('sub task title').setCreatedDate(new Date()).build())
                .build()
        when:
        taskDao.insert(task)
        then:
        thrown(UnsupportedDataOperationException)
    }

    def "should throw exception when trying to insert task with non-existing tag's names"() {
        given:
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle')
                .setCreatedDate(new Date())
                .setDescription('desc')
                .addTag(new Tag('123', 'mariusz', 'tagA', null, false))
                .setFinished(false)
                .setStartDate(DateMidnight.parse('2014-02-27').toDate())
                .setDueDate(DateMidnight.parse('2014-03-13').toDate())
                .build()
        when:
        taskDao.insert(task)
        then:
        thrown(UnsupportedDataOperationException)
    }

    def "should throw exception when trying to insert task without ownerId set"() {
        given:
        def task = new Task.TaskBuilder().setTitle("someTitle").setCreatedDate(new Date()).build()
        when:
        taskDao.insert(task)
        then:
        thrown(UnsupportedDataOperationException)
    }

    def "should set task's created date to now when inserting task and created date is not set"() {
        given: "task without created date set"
        def task = new Task.TaskBuilder().setOwnerId("mariusz").setTitle("title").build();
        when: "trying to insert task"
        def taskAfterUpdate = taskDao.insert(task)
        then:
        def taskInDb = tasksCollection.findOne(new BasicDBObject('_id', new ObjectId(taskAfterUpdate.getId())))
        taskInDb.get('created_date') > DateTime.now().minusSeconds(10).toDate()
    }

    def "should return list of tasks when user of given id has tasks"() {
        given:
        def expectedTasks = (1..5).collect {
            new Task.TaskBuilder().setOwnerId('mariusz').setTitle("taskTitle_$it").setCreatedDate(new Date()).build()
        }
        expectedTasks.each { taskDao.insert(it) }
        when:
        def tasksFromDb = taskDao.findAllByOwnerId('mariusz')
        then:
        tasksFromDb.each {
            assert it.getId() != null
            assert it.getTitle() ==~ /taskTitle_\d/
            assert it.getOwnerId() == 'mariusz'
            assert it.getCreatedDate() > DateTime.now().minusSeconds(10).toDate()
        }
    }

    def "should map task with subtasks from db to task object when retrieving tasks from db"() {
        given:
        tagsCollection.insert(new BasicDBObject([name:'next', owner_id:'mariusz', color:'red', visible_in_workview:true]))
        tagsCollection.insert(new BasicDBObject([name:'project', owner_id:'mariusz', color:'blue', visible_in_workview:true]))
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('tasksTitle')
                .setDescription('desc')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .setStartDate(DateMidnight.parse('2014-02-11').toDate())
                .setDueDate(DateMidnight.parse('2014-03-31').toDate())
                .setClosedDate(DateTime.parse('2014-04-13T20:16:32').toDate())
                .setFinished(true)
                .addTag(Tag.TagBuilder.start('mariusz', 'next').color('red').visibleInWorkView(true).build())
                .addTag(Tag.TagBuilder.start('mariusz', 'project').color('blue').visibleInWorkView(true).build())
                .build()
        def topTask = taskDao.insert(task)
        def subtask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('subtaskTitle')
                .setCreatedDate(DateTime.parse('2014-04-01T20:12:54').toDate())
                .build()
        subtask = taskDao.insert(subtask)
        taskDao.addSubtask('mariusz', topTask.id, subtask.id)
        when:
        def retrievedTask = taskDao.findAllByOwnerId('mariusz').first()
        then:
        retrievedTask.ownerId == 'mariusz'
        retrievedTask.title == 'tasksTitle'
        retrievedTask.description == 'desc'
        retrievedTask.createdDate == DateTime.parse('2014-01-21T12:32:11').toDate()
        retrievedTask.startDate == DateMidnight.parse('2014-02-11').toDate()
        retrievedTask.dueDate == DateMidnight.parse('2014-03-31').toDate()
        retrievedTask.closedDate == DateTime.parse('2014-04-13T20:16:32').toDate()
        retrievedTask.finished == true
        retrievedTask.tags == [Tag.TagBuilder.start('mariusz', 'next').build(), Tag.TagBuilder.start('mariusz', 'project').build()] as Set
        retrievedTask.subtasks.first().title == 'subtaskTitle'
        retrievedTask.subtasks.first().createdDate == DateTime.parse('2014-04-01T20:12:54').toDate()
    }

    def "should remove task of specified owner and id from DB"() {
        given:
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle').setCreatedDate(new Date()).build()
        def taskAfterUpdate = taskDao.insert(task)
        when:
        taskDao.remove('mariusz', taskAfterUpdate.id)
        then:
        tasksCollection.count(new BasicDBObject("_id", new ObjectId(taskAfterUpdate.id))) == 0
    }

    def "should remove all subtasks of task when removing task"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subtask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subSubtask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle3')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        parentTask = taskDao.insert(parentTask)
        subtask = taskDao.insert(subtask)
        subSubtask = taskDao.insert(subSubtask)
        taskDao.addSubtask('mariusz', parentTask.id, subtask.id)
        taskDao.addSubtask('mariusz', subtask.id, subSubtask.id)
        when:
        taskDao.remove('mariusz', parentTask.id)
        then:
        tasksCollection.getCount(new BasicDBObject('_id', new ObjectId(subtask.id))) == 0
        tasksCollection.getCount(new BasicDBObject('_id', new ObjectId(subSubtask.id))) == 0
    }

    def "should throw exception when trying to remove task and task is owned by another customer"() {
        given:
        def task = new Task.TaskBuilder().setOwnerId('onwer1').setTitle('taskTitle').setCreatedDate(new Date()).build()
        def taskAfterUpdate = taskDao.insert(task)
        when:
        taskDao.remove('owner1', taskAfterUpdate.id)
        then:
        thrown(NonExistingResourceOperationException)
    }

    def "should throw exception when trying to remove task and task with given id does not exists"() {
        when:
        taskDao.remove('owner', ObjectId.get().toString())
        then:
        thrown(NonExistingResourceOperationException)
    }

    def "should throw exception when trying to remove task specifying invalid task id"() {
        when:
        taskDao.remove('owner', 'invalidTaskId')
        then:
        thrown(NonExistingResourceOperationException)
    }

    def "should update task with new values when new task values specified for update"() {
        given:
        tagsCollection.insert(new BasicDBObject([name:'tagA', owner_id:'mariusz', color:null, visible_in_workview:false]))
        tagsCollection.insert(new BasicDBObject([name:'tagB', owner_id:'mariusz', color:'black', visible_in_workview:true]))
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .setDescription('desc')
                .addTag(new Tag('123', 'mariusz', 'tagA', null, false))
                .setFinished(false)
                .setStartDate(DateMidnight.parse('2014-02-27').toDate())
                .setDueDate(DateMidnight.parse('2014-03-13').toDate())
                .build()
        task = taskDao.insert(task);
        def taskToUpdate = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('updatedTaskTitle')
                .setId(task.id)
                .setCreatedDate(DateTime.parse('2014-02-21T12:32:11').toDate())
                .setDescription('updated description')
                .addTag(new Tag('123', 'mariusz', 'tagA', null, false))
                .addTag(new Tag('124', 'mariusz', 'tagB', 'black', true))
                .setStartDate(DateMidnight.parse('2014-02-28').toDate())
                .setDueDate(DateMidnight.parse('2014-03-14').toDate())
                .setClosedDate(DateTime.parse('2014-05-12T11:31:41').toDate())
                .setFinished(true)
                .build()
        when:
        taskDao.update('mariusz', taskToUpdate)
        then:
        def retrievedTask = taskDao.findAllByOwnerId('mariusz').first()
        retrievedTask.id == task.id
        retrievedTask.ownerId == 'mariusz'
        retrievedTask.title == 'updatedTaskTitle'
        retrievedTask.description == 'updated description'
        retrievedTask.startDate == DateMidnight.parse('2014-02-28').toDate()
        retrievedTask.dueDate == DateMidnight.parse('2014-03-14').toDate()
        retrievedTask.closedDate == DateTime.parse('2014-05-12T11:31:41').toDate()
        retrievedTask.finished
        retrievedTask.tags == [Tag.TagBuilder.start('mariusz', 'tagA').build(), Tag.TagBuilder.start('mariusz', 'tagB').build()] as Set
    }

    def "should created date stay unchanged when trying to update task with changed created date"() {
        given:
        tagsCollection.insert(new BasicDBObject([name:'tagA', owner_id:'mariusz', color:null, visible_in_workview:false]))
        tagsCollection.insert(new BasicDBObject([name:'tagB', owner_id:'mariusz', color:'black', visible_in_workview:true]))
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        task = taskDao.insert(task);
        def taskToUpdate = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('updatedTaskTitle')
                .setCreatedDate(DateTime.parse('2014-02-25T12:32:11').toDate()).setId(task.id)
                .build()
        when:
        taskDao.update('mariusz', taskToUpdate)
        then:
        def retrievedTask = taskDao.findAllByOwnerId('mariusz').first()
        retrievedTask.createdDate == DateTime.parse('2014-01-21T12:32:11').toDate()
    }

    def "should return task object after update when update has been done"() {
        given:
        tagsCollection.insert(new BasicDBObject([name:'tagA', owner_id:'mariusz', color:null, visible_in_workview:false]))
        tagsCollection.insert(new BasicDBObject([name:'tagB', owner_id:'mariusz', color:'black', visible_in_workview:true]))
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        task = taskDao.insert(task);
        def taskToUpdate = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('updatedTaskTitle')
                .setCreatedDate(DateTime.parse('2014-02-25T12:32:11').toDate())
                .setId(task.id)
                .build()
        when:
        def taskAfterUpdate = taskDao.update('mariusz', taskToUpdate)
        then:
        taskAfterUpdate.id == task.id
        taskAfterUpdate.title == taskToUpdate.title
    }

    def "should throw exception when trying to update task without ownerId set"() {
        given:
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        when:
        taskDao.update(null, task)
        then:
        thrown(NullPointerException)
    }

    def "should throw exception when task of given id to update not exists"() {
        given:
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .setId(ObjectId.get().toString())
                .build()
        when:
        taskDao.update('mariusz', task)
        then:
        thrown(NonExistingResourceOperationException)
    }

    def "should throw exception when trying to update task by adding non-existing tags"() {
        given:
        def nonExistingTag = Tag.TagBuilder.start('mariusz', 'nonExisting').id(ObjectId.get().toString()).build()
        Task task = new Task.TaskBuilder().setOwnerId("mariusz").setTitle("title").build()
        task = taskDao.insert(task)
        Task taskWithNonExistingTag = new Task.TaskBuilder().setId(task.id).setOwnerId("mariusz").setTitle("title")
                .addTag(nonExistingTag)build()
        when:
        taskDao.update('mariusz', taskWithNonExistingTag)
        then:
        thrown(UnsupportedDataOperationException)
    }

    def "should change task's path in DB when setting task as subtask"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subtask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        parentTask = taskDao.insert(parentTask)
        subtask = taskDao.insert(subtask)
        when:
        taskDao.addSubtask('mariusz', parentTask.id, subtask.id)
        then:
        tasksCollection.findOne(new BasicDBObject([_id:new ObjectId(subtask.id)])).path == [parentTask.id]
    }

    def "should throw non existing response exception when trying to add subtask to another customer parent task"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('owner1').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subtask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        parentTask = taskDao.insert(parentTask)
        subtask = taskDao.insert(subtask)
        when:
        taskDao.addSubtask('mariusz', parentTask.id, subtask.id)
        then:
        thrown(NonExistingResourceOperationException)
    }

    def "should throw non existing response exception when trying to add subtask of another customer"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subtask = new Task.TaskBuilder().setOwnerId('owner1').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        parentTask = taskDao.insert(parentTask)
        subtask = taskDao.insert(subtask)
        when:
        taskDao.addSubtask('mariusz', parentTask.id, subtask.id)
        then:
        thrown(NonExistingResourceOperationException)
    }

    def "should return parent task with all subtasks as tree when subtasks has been added"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subtask1 = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subtask2 = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle3')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        parentTask = taskDao.insert(parentTask)
        subtask1 = taskDao.insert(subtask1)
        subtask2 = taskDao.insert(subtask2)
        when:
        taskDao.addSubtask('mariusz', parentTask.id, subtask1.id)
        def taskAfterUpdate = taskDao.addSubtask('mariusz', parentTask.id, subtask2.id)
        then:
        taskAfterUpdate.id == parentTask.id
        taskAfterUpdate.subtasks.size() == 2
        taskAfterUpdate.subtasks.any { it.id == subtask1.id }
        taskAfterUpdate.subtasks.any { it.id == subtask2.id }
    }

    def "should set full path to task when setting task as subtask of a non-top level task"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subtask1 = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subtask2 = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle3')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        parentTask = taskDao.insert(parentTask)
        subtask1 = taskDao.insert(subtask1)
        subtask2 = taskDao.insert(subtask2)
        taskDao.addSubtask('mariusz', parentTask.id, subtask1.id)
        when:
        taskDao.addSubtask('mariusz', subtask1.id, subtask2.id)
        then:
        tasksCollection.findOne(new BasicDBObject([_id:new ObjectId(subtask2.id)])).path == [parentTask.id,subtask1.id]
    }

    def "should throw exception when trying to add task as subtask to itself"() {
        given:
        def task = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        task = taskDao.insert(task)
        when:
        taskDao.addSubtask('mariusz', task.id, task.id)
        then:
        thrown(UnsupportedDataOperationException)
    }

    def "should throw exception when trying to add task as subtask to each own subtask"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subtask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subSubtask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle3')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        parentTask = taskDao.insert(parentTask)
        subtask = taskDao.insert(subtask)
        subSubtask = taskDao.insert(subSubtask)
        taskDao.addSubtask('mariusz', parentTask.id, subtask.id)
        taskDao.addSubtask('mariusz', subtask.id, subSubtask.id)
        when:
        taskDao.addSubtask('mariusz', subSubtask.id, parentTask.id)
        then:
        thrown(UnsupportedDataOperationException)
    }

    def "should change path of all subtasks of moved task when moving task to different parent"() {
        given:
        def topParentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subtask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subSubtask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle3')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        topParentTask = taskDao.insert(topParentTask)
        parentTask = taskDao.insert(parentTask)
        subtask = taskDao.insert(subtask)
        subSubtask = taskDao.insert(subSubtask)
        taskDao.addSubtask('mariusz', parentTask.id, subtask.id)
        taskDao.addSubtask('mariusz', subtask.id, subSubtask.id)
        when:
        taskDao.addSubtask('mariusz', topParentTask.id, parentTask.id)
        then:
        def subtaskDbAfterUpdate = tasksCollection.findOne(new BasicDBObject('_id', new ObjectId(subtask.id)))
        subtaskDbAfterUpdate.path == [topParentTask.id,parentTask.id]
        def subSubtaskDbAfterUpdate = tasksCollection.findOne(new BasicDBObject('_id', new ObjectId(subSubtask.id)))
        subSubtaskDbAfterUpdate.path == [topParentTask.id,parentTask.id,subtask.id]
    }

    def "should remove task's path when task is moved to top level"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        parentTask = taskDao.insert(parentTask)
        subTask = taskDao.insert(subTask)
        taskDao.addSubtask('mariusz', parentTask.id, subTask.id)
        when:
        taskDao.moveToTopLevel('mariusz', subTask.id)
        then:
        def subtaskAfterUpdateDb = tasksCollection.findOne(new BasicDBObject('_id', new ObjectId(subTask.id)))
        subtaskAfterUpdateDb.get('path').isEmpty()
    }

    def "should return updated task when task has been moved to top level"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        parentTask = taskDao.insert(parentTask)
        subTask = taskDao.insert(subTask)
        taskDao.addSubtask('mariusz', parentTask.id, subTask.id)
        when:
        def taskAfterUpdate = taskDao.moveToTopLevel('mariusz', subTask.id)
        then:
        taskAfterUpdate.id == subTask.id
    }

    def "should throw NonExistingResourceOperationException when trying to move non-existing task to top-level"() {
        when:
        taskDao.moveToTopLevel('mariusz', ObjectId.get().toString())
        then:
        thrown(NonExistingResourceOperationException);
    }

    def "should set subtask to finished when parent task state is updated to finished"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        def subTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate())
                .build()
        parentTask = taskDao.insert(parentTask)
        subTask = taskDao.insert(subTask)
        taskDao.addSubtask('mariusz', parentTask.id, subTask.id)
        when:
        def parentTaskFinished = new Task.TaskBuilder().setId(parentTask.id).setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate()).setFinished(true)
                .build()
        taskDao.update('mariusz', parentTaskFinished)
        then:
        tasksCollection.findOne(new BasicDBObject('_id', new ObjectId(subTask.id))).get("finished") == true
    }

    def "should set parents tasks to NOT finished when subtask state is updated to NOT finished"() {
        given:
        def parentTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle1')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate()).setFinished(true)
                .build()
        def subTask = new Task.TaskBuilder().setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate()).setFinished(true)
                .build()
        parentTask = taskDao.insert(parentTask)
        subTask = taskDao.insert(subTask)
        taskDao.addSubtask('mariusz', parentTask.id, subTask.id)
        when:
        def subTaskFinished = new Task.TaskBuilder().setId(subTask.id).setOwnerId('mariusz').setTitle('taskTitle2')
                .setCreatedDate(DateTime.parse('2014-01-21T12:32:11').toDate()).setFinished(false)
                .build()
        taskDao.update('mariusz', subTaskFinished)
        then:
        tasksCollection.findOne(new BasicDBObject('_id', new ObjectId(parentTask.id))).get("finished") == false
    }

    def "should update task state (finished) with additional DB call only when update request has changed task state"() {
        given: "unfinished task exists"
        def tasksCollectionMock = Mock(DBCollection)
        tasksCollectionMock.insert(_) >> { args -> tasksCollection.insert(args[0]) }
        tasksCollectionMock.findAndModify(_,_,_,_,_,_,_) >>> [new BasicDBObject([_id: ObjectId.get(), title: 'title', owner_id: 'mariusz', finished: false, tags:[]]), new BasicDBObject([_id: ObjectId.get(), title: 'title', owner_id: 'mariusz', finished: false, tags:[]])]
        tasksCollectionMock.findOne(_, _) >> new BasicDBObject([_id: ObjectId.get(), title: 'title', owner_id: 'mariusz', finished: false, tags:[], path: []])
        tasksCollectionMock.find(_) >> { args -> tasksCollection.find(args[0]) }
        TaskDao taskDaoWithMocks = new TaskDao(tasksCollectionMock, tagDao, dbTasksConverter)
        def existingTask = new Task.TaskBuilder().setOwnerId("mariusz").setTitle("title").build();
        existingTask = taskDaoWithMocks.insert(existingTask)
        when: "request to update task, but status remain unchanged"
        taskDaoWithMocks.update('mariusz', existingTask)
        then: "second DB call to update task status is not made"
        0 * tasksCollectionMock.update(_, new BasicDBObject('$set', new BasicDBObject('finished', existingTask.isFinished())), _, _);
    }
}