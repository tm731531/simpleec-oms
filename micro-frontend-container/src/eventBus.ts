/**
 * Type-safe event emitter for inter-microapp communication
 * Provides subscribe, unsubscribe, and emit functionality with no memory leaks
 */

import type { EventMap, EventCallback } from './types'

/**
 * EventBus class provides a type-safe event emission and subscription system
 * for communication between microapps in the qiankun container
 */
class EventBus {
  /**
   * Storage for event listeners
   * Maps event names to arrays of callbacks
   */
  private listeners: Map<
    keyof EventMap,
    Set<EventCallback<keyof EventMap>>
  > = new Map()

  /**
   * Subscribe to an event
   * @param event - The event name to listen for
   * @param callback - Function to call when event is emitted
   * @returns Unsubscribe function for cleanup
   */
  public on<K extends keyof EventMap>(
    event: K,
    callback: EventCallback<K>
  ): () => void {
    // Initialize event listener set if it doesn't exist
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set())
    }

    // Add the callback to the event's listener set
    const eventListeners = this.listeners.get(event)!
    eventListeners.add(callback as EventCallback<keyof EventMap>)

    // Return unsubscribe function
    return () => {
      this.off(event, callback)
    }
  }

  /**
   * Unsubscribe from an event
   * @param event - The event name
   * @param callback - The callback function to remove
   */
  public off<K extends keyof EventMap>(
    event: K,
    callback: EventCallback<K>
  ): void {
    const eventListeners = this.listeners.get(event)

    if (!eventListeners) {
      return
    }

    eventListeners.delete(callback as EventCallback<keyof EventMap>)

    // Clean up empty listener sets to prevent memory leaks
    if (eventListeners.size === 0) {
      this.listeners.delete(event)
    }
  }

  /**
   * Subscribe to an event for a single occurrence
   * Automatically unsubscribes after first emission
   * @param event - The event name to listen for
   * @param callback - Function to call when event is emitted
   * @returns Unsubscribe function for manual cleanup if needed
   */
  public once<K extends keyof EventMap>(
    event: K,
    callback: EventCallback<K>
  ): () => void {
    const wrappedCallback: EventCallback<K> = (payload: EventMap[K]) => {
      callback(payload)
      this.off(event, wrappedCallback)
    }

    return this.on(event, wrappedCallback)
  }

  /**
   * Emit an event to all subscribers
   * Handles errors gracefully without stopping other listeners
   * @param event - The event name to emit
   * @param payload - Data to pass to listeners
   */
  public emit<K extends keyof EventMap>(
    event: K,
    payload: EventMap[K]
  ): void {
    const eventListeners = this.listeners.get(event)

    if (!eventListeners || eventListeners.size === 0) {
      return
    }

    // Execute all listeners for this event
    // Use Array.from to avoid issues with concurrent modifications
    for (const callback of Array.from(eventListeners)) {
      try {
        (callback as EventCallback<K>)(payload)
      } catch (error) {
        // Log error but don't stop other listeners from executing
        console.error(
          `Error in event listener for '${String(event)}':`,
          error
        )
      }
    }
  }

  /**
   * Emit an event and wait for all async listeners to complete
   * @param event - The event name to emit
   * @param payload - Data to pass to listeners
   */
  public async emitAsync<K extends keyof EventMap>(
    event: K,
    payload: EventMap[K]
  ): Promise<void> {
    const eventListeners = this.listeners.get(event)

    if (!eventListeners || eventListeners.size === 0) {
      return
    }

    // Execute all listeners concurrently
    const promises: Promise<void>[] = []

    for (const callback of Array.from(eventListeners)) {
      const result = (callback as EventCallback<K>)(payload)

      if (result instanceof Promise) {
        promises.push(
          result.catch((error) => {
            console.error(
              `Error in async event listener for '${String(event)}':`,
              error
            )
          })
        )
      }
    }

    await Promise.all(promises)
  }

  /**
   * Get the number of listeners for an event
   * Useful for debugging
   * @param event - The event name (optional, returns total if not specified)
   */
  public listenerCount(event?: keyof EventMap): number {
    if (event === undefined) {
      return Array.from(this.listeners.values()).reduce(
        (sum, set) => sum + set.size,
        0
      )
    }

    return this.listeners.get(event)?.size ?? 0
  }

  /**
   * Remove all listeners for an event or all events
   * Useful for cleanup when microapp is unmounted
   * @param event - The event name (optional, clears all if not specified)
   */
  public clear(event?: keyof EventMap): void {
    if (event === undefined) {
      this.listeners.clear()
    } else {
      this.listeners.delete(event)
    }
  }
}

// Export singleton instance
export const eventBus = new EventBus()

// Also export the class for testing or specialized use cases
export default EventBus
