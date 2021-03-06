package net.floodlightcontroller.core.internal;

import java.util.Map.Entry;

import javax.annotation.Nonnull;

import java.util.Date;

import net.dsc.cluster.HARole;
import net.dsc.cluster.IClusterService;
import net.dsc.cluster.RoleInfo;
import net.dsc.hazelcast.listener.IHAListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchBackend;
import net.floodlightcontroller.core.IShutdownService;
import net.floodlightcontroller.core.internal.Controller.IUpdate;

import org.projectfloodlight.openflow.protocol.OFControllerRole;
import org.projectfloodlight.openflow.types.DatapathId;
import org.python.modules.synchronize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.hazelcast.core.IMap;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A utility class to manage the <i>controller roles</i>.
 * A utility class to manage the <i>controller roles</i>  as opposed
 * to the switch roles. The class manages the controllers current role,
 * handles role change requests, and maintains the list of connected
 * switch(-channel) so it can notify the switches of role changes.
 *	一个工具类来管理控制器角色而不是交换机角色。
 *	管理控制器当前角色，处理角色变化请求，维护一个交换机连接列表，所以能通知交换机身份变化
 *	
 * We need to ensure that every connected switch is always send the
 * correct role. Therefore, switch add, sending of the initial role, and
 * changing role need to use mutexes to ensure this. This has the ugly
 * side-effect of requiring calls between controller and OFChannelHandler
 *	我们需要确保每个交换机连接总是发送到正确的角色。
 *	因此，交换机添加，发送初始角色，更改角色都需要互斥来确保。
 *	这有一个丑陋的副作用要求请求在控制器和OFChannelHandler之间
 * This class is fully thread safe. Its method can safely be called from
 * any thread.
 *	这个类似完全线程安全的。
 *	其中的方法能被其他线程随意调用
 * @author gregor
 *
 */
public class RoleManager {
	/**
	 * 角色信息
	 */
    private volatile RoleInfo currentRoleInfo;
    /**
     * 控制器
     */
    private final Controller controller;
    /**
     * 关闭服务
     */
    private final IShutdownService shutdownService;
    /**
     * 角色管理器计数器
     */
    private final RoleManagerCounters counters;
    /**
     * 集群角色集合
     */
    private final IClusterService clusterService;
    private static final Logger log =
            LoggerFactory.getLogger(RoleManager.class);
    /**
     * @param role initial role
     * @param roleChangeDescription initial value of the change description
     * @throws NullPointerException if role or roleChangeDescription is null
     */
    public RoleManager(@Nonnull Controller controller,
            @Nonnull IShutdownService shutdownService,
            @Nonnull HARole role,
            @Nonnull String roleChangeDescription,
            @Nonnull IClusterService clusterService) {
        Preconditions.checkNotNull(controller, "controller must not be null");
        Preconditions.checkNotNull(role, "role must not be null");
        Preconditions.checkNotNull(roleChangeDescription, "roleChangeDescription must not be null");
        Preconditions.checkNotNull(shutdownService, "shutdownService must not be null");
        Preconditions.checkNotNull(clusterService, "clusterService must not be null");
        
        this.currentRoleInfo = new RoleInfo(role,
                                       roleChangeDescription,
                                       new Date());
        this.controller = controller;
        this.shutdownService = shutdownService;
        this.counters = new RoleManagerCounters(controller.getDebugCounter());
        this.clusterService=clusterService;
    }

    /**
     * Re-assert a role for the given channel handler.
     *	重置一个角色对于给定的连接处理
     * The caller specifies the role that should be reasserted. We only
     * reassert the role if the controller's current role matches the
     * reasserted role and there is no role request for the reasserted role
     * pending.
     * 调用者指定应该被重声的角色。
     * 我们只在控制器当前角色匹配重声角色的情况下重声角色。
     * 没有重声角色请求会被挂起
     * @param ofSwitchHandshakeHandler The OFChannelHandler on which we should reassert.
     * @param role The role to reassert
     */
    public synchronized void reassertRole(OFSwitchHandshakeHandler ofSwitchHandshakeHandler, HARole role) {
        // check if the requested reassertion actually makes sense
        if (this.getRole() != role)
            return;
        ofSwitchHandshakeHandler.sendRoleRequestIfNotPending(this.getRole().getOFRole());
    }

    /**
     * Set the controller's new role and notify switches.
     *	设置一个新的身份并通知交换机
     * This method updates the controllers current role and notifies all
     * connected switches of the new role is different from the current
     * role. We dampen calls to this method. See class description for
     * details.
     * 这个方法更新控制器当前角色并通知所有连接的交换机。
     * 我们抵制调用这个方法，详情看类描述
     * 
     * @param role The new role.
     * @param roleChangeDescription A textual description of why the role
     * was changed. For information purposes only. 描述为什么角色变化的文本信息。
     * @throws NullPointerException if role or roleChangeDescription is null
     */
    public synchronized void setRole(@Nonnull HARole role, @Nonnull String roleChangeDescription) {
        Preconditions.checkNotNull(role, "role must not be null");
        Preconditions.checkNotNull(roleChangeDescription, "roleChangeDescription must not be null");

        if (role == getRole()) {
            counters.setSameRole.increment();
            log.debug("Received role request for {} but controller is "
                    + "already {}. Ignoring it.", role, this.getRole());
            return;
        }

        if (this.getRole() == HARole.STANDBY && role == HARole.ACTIVE) {
            // At this point we are guaranteed that we will execute the code
            // below exactly once during the lifetime of this process! And
            // it will be a to MASTER transition
        	//在这种情况下,
            counters.setRoleMaster.increment();
        }

        log.info("Received role request for {} (reason: {})."
                + " Initiating transition", role, roleChangeDescription);

        currentRoleInfo =
                new RoleInfo(role, roleChangeDescription, new Date());

        getController().addUpdateToQueue(new HARoleUpdate(role));
        getController().addUpdateToQueue(new SwitchRoleUpdate(role));

    }

    @SuppressFBWarnings(value="UG_SYNC_SET_UNSYNC_GET",
                        justification = "setter is synchronized for mutual exclusion, "
                                + "currentRoleInfo is volatile, so no sync on getter needed")
    public synchronized HARole getRole() {
        return currentRoleInfo.getRole();
    }

    public synchronized OFControllerRole getOFControllerRole(DatapathId dpid) {
    	
    	System.out.println(clusterService.getMasterMap().keySet());
    	if(clusterService.getMasterMap().containsKey(dpid.toString())){
    		log.info("SLAVE:{}<-------->{}",controller.getControllerModel().getControllerId(),dpid.toString());
    		return OFControllerRole.ROLE_SLAVE;
    	}
    	else{
    		log.info("MASTER:{}<-------->{}",controller.getControllerModel().getControllerId(),dpid.toString());
    		return OFControllerRole.ROLE_MASTER;
    	}
    			
    }
    /**
     * Return the RoleInfo object describing the current role.
     *
     * Return the RoleInfo object describing the current role. The
     * RoleInfo object is used by REST API users.
     * @return the current RoleInfo object
     */
    public RoleInfo getRoleInfo() {
        return currentRoleInfo;
    }

    private void attemptActiveTransition() {
         if(!switchesHaveAnotherMaster()){
             // No valid cluster controller connections found, become ACTIVE!
        	 //没有找到有效的集群控制器连接，成为ACTIVE
             setRole(HARole.ACTIVE, "Leader election assigned ACTIVE role");
         }
     }

    /**
     * Iterates over all the switches and checks to see if they have controller
     * connections that points towards another master controller.
     * 迭代所有的交换机并检查是否他们已连接其他主控制器
     * @return
     */
    private boolean switchesHaveAnotherMaster() {
        IOFSwitchService switchService = getController().getSwitchService();

        for(Entry<DatapathId, IOFSwitch> switchMap : switchService.getAllSwitchMap().entrySet()){
            IOFSwitchBackend sw = (IOFSwitchBackend) switchMap.getValue();
            if(sw.hasAnotherMaster()){
                return true;
            }
        }
        return false;
    }

    public void notifyControllerConnectionUpdate() {
        if(currentRoleInfo.getRole() != HARole.ACTIVE) {
            attemptActiveTransition();
        }
    }

    /**
     * Update message indicating controller's role has changed.
     * RoleManager, which enqueues these updates guarantees that we will
     * only have a single transition from SLAVE to MASTER.
     * 更新消息表明控制器角色发生变化
     * RoleManager需要保证只有一个从SLAVE到MASTER的变化
     * When the role update from master to slave is complete, the HARoleUpdate
     * will terminate floodlight.
     * 当角色完成从master到slave，HARoleUpdate将终止floodlight
     */
    private class HARoleUpdate implements IUpdate {
        private final HARole newRole;
        public HARoleUpdate(HARole newRole) {
            this.newRole = newRole;
        }

        @Override
        public void dispatch() {
            if (log.isDebugEnabled()) {
                log.debug("Dispatching HA Role update newRole = {}",
                          newRole);
            }
            for (IHAListener listener : getController().haListeners.getOrderedListeners()) {
                if (log.isTraceEnabled()) {
                    log.trace("Calling HAListener {} with transitionTo{}",
                              listener.getName(), newRole);
                }
                switch(newRole) {
                    case ACTIVE:
                        listener.transitionToActive();
                        break;
                    case STANDBY:
                        listener.transitionToStandby();
                        break;
                }
           }

           getController().setNotifiedRole(newRole);

           if(newRole == HARole.STANDBY) {
               String reason = String.format("Received role request to "
                       + "transition from ACTIVE to STANDBY (reason: %s)",
                       getRoleInfo().getRoleChangeDescription());
               shutdownService.terminate(reason, 0);
           }
        }
    }

    public class SwitchRoleUpdate implements IUpdate {
        private final HARole role;

        public SwitchRoleUpdate(HARole role) {
            this.role = role;
        }

        @Override
        public void dispatch() {
            if (log.isDebugEnabled()) {
                log.debug("Dispatching switch role update newRole = {}, switch role = {}",
                          this.role, this.role.getOFRole());
            }

            for (OFSwitchHandshakeHandler h: getController().getSwitchService().getSwitchHandshakeHandlers()){
            	if(clusterService.getMasterMap().keySet().contains(h.getDpid()))
            		h.sendRoleRequest(OFControllerRole.ROLE_SLAVE);
            	else
            		h.sendRoleRequest(OFControllerRole.ROLE_MASTER);
            }
        }
    }

    public RoleManagerCounters getCounters() {
        return this.counters;
    }

	public Controller getController() {
		return controller;
	}
	
}
